"""Release the local model icon library while retaining the current production runtime."""
import hashlib
import getpass
import json
import os
import re
import subprocess
import sys
import zipfile
from pathlib import Path
from urllib.request import urlopen

root = Path(__file__).resolve().parents[2]
revision = sys.argv[2] if len(sys.argv) > 2 else ''
assert not revision or re.fullmatch(r'r[0-9]+', revision), 'Revision must be r followed by digits'
suffix = '-' + revision if revision else ''
out = root / ('.local-ops/releases/model-icons-release-20261014' + suffix)
tag = 'ai-gateway-standalone:model-icons-20261014' + suffix
rollback_tag = 'ai-gateway-standalone:rollback-model-icons-20261014' + suffix
static = 'BOOT-INF/classes/static/admin/'
packages = {
    'org/unreal/modelrouter/catalog/': ['ModelIconLibrary', 'ModelSquareDtos', 'ModelSquareService'],
    'org/unreal/modelrouter/persistence/jpa/entity/': ['ModelSquareContentEntity'],
}
resources = ['db/migration/postgresql/V24__model_square_icons.sql'] + [
    file.relative_to(root / 'src/main/resources').as_posix()
    for file in sorted((root / 'src/main/resources/static/model-icons').glob('*')) if file.is_file()
]




def run(args, **kwargs):
    result = subprocess.run(args, capture_output=True, **kwargs)
    if result.returncode:
        (out / 'command-error.log').write_bytes(result.stdout + result.stderr)
        raise RuntimeError('Command failed; details saved in the private release directory')
    return result.stdout


def inspect(name):
    return json.loads(run(['docker', 'inspect', name]))[0]


def save(name, data):
    (out / name).write_text(json.dumps(data, ensure_ascii=False, indent=2), encoding='utf-8')


def read(name):
    return json.loads((out / name).read_text(encoding='utf-8'))


def snapshot_compose(container, image):
    config, host = container['Config'], container['HostConfig']
    service = {
        'image': image, 'container_name': container['Name'].lstrip('/'),
        'environment': {key: value.replace('$', '$$') for key, value in
                        (entry.split('=', 1) for entry in config['Env'])},
        'restart': host['RestartPolicy']['Name'],
        'hostname': config['Hostname'], 'working_dir': config['WorkingDir'],
        'extra_hosts': host.get('ExtraHosts') or [],
        'logging': {'driver': host['LogConfig']['Type'], 'options': host['LogConfig']['Config']},
        'ports': [], 'volumes': [], 'networks': {},
    }
    for field in ['entrypoint', 'command', 'user', 'stop_signal']:
        source = {'entrypoint': 'Entrypoint', 'command': 'Cmd', 'user': 'User', 'stop_signal': 'StopSignal'}[field]
        if config.get(source):
            value = config[source]
            service[field] = [item.replace('$', '$$') for item in value] if isinstance(value, list) else value.replace('$', '$$')
    health = config.get('Healthcheck') or {}
    if health:
        service['healthcheck'] = {'test': health['Test']}
        for old, new in [('Interval', 'interval'), ('Timeout', 'timeout'), ('StartPeriod', 'start_period')]:
            if old in health:
                service['healthcheck'][new] = str(health[old]) + 'ns'
        if 'Retries' in health:
            service['healthcheck']['retries'] = health['Retries']
    for port, bindings in (host['PortBindings'] or {}).items():
        number, protocol = port.split('/')
        for binding in bindings:
            service['ports'].append({'target': int(number), 'published': binding['HostPort'],
                                     'host_ip': binding['HostIp'], 'protocol': protocol})
    volumes, networks = {}, {}
    for index, mount in enumerate(container['Mounts']):
        item = {'type': mount['Type'], 'target': mount['Destination'], 'read_only': not mount['RW']}
        if mount['Type'] == 'volume':
            key = 'live-volume-' + str(index)
            volumes[key] = {'external': True, 'name': mount['Name']}
            item['source'] = key
        elif mount['Type'] == 'bind':
            item['source'] = mount['Source']
            item['bind'] = {'create_host_path': False}
        else:
            raise RuntimeError('Unsupported mount type: ' + mount['Type'])
        service['volumes'].append(item)
    for index, (name, network) in enumerate(container['NetworkSettings']['Networks'].items()):
        key = 'live-network-' + str(index)
        networks[key] = {'external': True, 'name': name}
        service['networks'][key] = {'aliases': [alias for alias in (network.get('Aliases') or [])
                                               if alias not in [container['Id'][:12], container['Id']]]}
    return {'services': {'gateway': service}, 'volumes': volumes, 'networks': networks}


def compose(filename):
    return ['docker', 'compose', '-p', read('manifest.json')['project'], '-f', str(out / filename)]


def runtime_signature(container):
    config, host = container['Config'], container['HostConfig']
    return {
        'environment': sorted(config['Env']), 'ports': host['PortBindings'],
        'mounts': sorted((m['Type'], m.get('Name') or m['Source'], m['Destination'], m['RW'])
                         for m in container['Mounts']),
        'networks': sorted(container['NetworkSettings']['Networks']),
        'entrypoint': config['Entrypoint'], 'command': config['Cmd'],
        'workingDir': config['WorkingDir'], 'user': config['User'],
        'healthcheck': config.get('Healthcheck'), 'restart': host['RestartPolicy'],
        'logging': host['LogConfig'], 'extraHosts': host['ExtraHosts'],
    }


mode = sys.argv[1]
if mode == 'prepare':
    out.mkdir(parents=True, exist_ok=True)
    assert not (out / 'manifest.json').exists(), 'Existing rollback snapshot must not be overwritten'
    if os.name == 'nt':
        run(['icacls', str(out), '/inheritance:r', '/grant:r', getpass.getuser() + ':(OI)(CI)F',
             '*S-1-5-18:(OI)(CI)F', '*S-1-5-32-544:(OI)(CI)F'])
    live = inspect('ai-gateway-standalone')
    assert live['State']['Status'] == 'running'
    save('container-before.json', live)
    others = {}
    for name in run(['docker', 'ps', '--format', '{{.Names}}']).decode().splitlines():
        if name != 'ai-gateway-standalone':
            container = inspect(name)
            others[name] = {'id': container['Id'], 'startedAt': container['State']['StartedAt']}
    save('other-containers-before.json', others)
    (out / 'database-before.dump').write_bytes(run(['docker', 'exec', 'ai-gateway-postgres', 'pg_dump',
                                                    '-U', 'ai_gateway', '-d', 'ai_gateway', '-Fc']))
    run(['docker', 'cp', 'ai-gateway-standalone:/app/app.jar', str(out / 'previous-app.jar')])
    run(['docker', 'tag', live['Image'], rollback_tag])
    save('release.compose.json', snapshot_compose(live, tag))
    save('rollback.compose.json', snapshot_compose(live, rollback_tag))
    save('manifest.json', {'previousContainer': live['Id'], 'previousImage': live['Image'],
                           'project': live['Config']['Labels']['com.docker.compose.project'], 'tag': tag})
    (out / 'Dockerfile').write_text('FROM ' + rollback_tag + '\nCOPY app.jar /app/app.jar\n', encoding='utf-8')
    (out / '.dockerignore').write_text('*\n!Dockerfile\n!app.jar\n', encoding='utf-8')
    with zipfile.ZipFile(out / 'previous-app.jar') as old:
        assert 'BOOT-INF/classes/org/unreal/modelrouter/auth/local/LocalAdministratorService.class' in old.namelist(), 'Required live administrator service missing'
    print('Live image, database, mounts, network and runtime configuration backed up.')
elif mode == 'build':
    updates = {}
    for package, names in packages.items():
        for name in names:
            directory = root / 'target/classes' / package
            source = root / 'src/main/java' / package / (name + '.java')
            files = list(directory.glob(name + '.class')) + list(directory.glob(name + '$*.class'))
            assert files and all(f.stat().st_mtime >= source.stat().st_mtime for f in files), 'Compilation is stale'
            for file in files:
                updates['BOOT-INF/classes/' + file.relative_to(root / 'target/classes').as_posix()] = file.read_bytes()
    for resource in resources:
        updates['BOOT-INF/classes/' + resource] = (root / 'src/main/resources' / resource).read_bytes()
    assert (root / 'frontend/dist/index.html').stat().st_mtime >= max(
        f.stat().st_mtime for f in (root / 'frontend/src').rglob('*') if f.is_file()), 'Frontend build is stale'
    for file in sorted((root / 'frontend/dist').rglob('*')):
        if file.is_file():
            updates[static + file.relative_to(root / 'frontend/dist').as_posix()] = file.read_bytes()
    with zipfile.ZipFile(out / 'previous-app.jar') as old, zipfile.ZipFile(out / 'app.jar', 'w') as new:
        for entry in old.infolist():
            if not entry.filename.startswith(static) and entry.filename not in updates:
                new.writestr(entry, old.read(entry.filename))
        for name, value in updates.items():
            new.writestr(name, value, compress_type=zipfile.ZIP_DEFLATED)
    with zipfile.ZipFile(out / 'previous-app.jar') as old, zipfile.ZipFile(out / 'app.jar') as new:
        assert all(old.read(name) == new.read(name) for name in old.namelist()
                   if not name.startswith(static) and name not in updates), 'Unrelated backend contents changed'
    save('updated-entries.json', sorted(updates))
    (out / 'build.log').write_bytes(run(['docker', 'build', '-t', tag, str(out)]))
    manifest = read('manifest.json')
    assert inspect('ai-gateway-standalone')['Id'] == manifest['previousContainer'], 'Live deployment changed'
    manifest.update(image=inspect(tag)['Id'], jarSha256=hashlib.sha256((out / 'app.jar').read_bytes()).hexdigest())
    save('manifest.json', manifest)
    (out / 'compose-validation.json').write_bytes(run(compose('release.compose.json') + ['config', '--format', 'json']))
    print('Model icon release image built; existing runtime configuration validated.')
elif mode == 'rehearse':
    rehearsal = 'ai-gateway-model-icons-release-check-20261014'
    run(['docker', 'run', '--detach', '--rm', '--name', rehearsal,
         '--network', 'none', '-e', 'POSTGRES_PASSWORD=rehearsal-only', 'postgres:16-alpine'])
    try:
        import time
        for attempt in range(30):
            ready = subprocess.run(['docker', 'exec', rehearsal, 'pg_isready', '-U', 'postgres'], capture_output=True)
            if ready.returncode == 0: break
            time.sleep(1)
        else: raise RuntimeError('Rehearsal database did not become ready')
        run(['docker', 'exec', rehearsal, 'psql', '-U', 'postgres', '-v', 'ON_ERROR_STOP=1', '-c', 'CREATE ROLE ai_gateway LOGIN SUPERUSER;'])
        run(['docker', 'exec', '-i', rehearsal, 'pg_restore', '-U', 'postgres', '-d', 'postgres', '--exit-on-error'],
            input=(out / 'database-before.dump').read_bytes())
        migration = (root / 'src/main/resources' / resources[0]).read_bytes()
        (out / 'migration-rehearsal.log').write_bytes(run(['docker', 'exec', '-i', rehearsal, 'psql', '-U', 'postgres',
            '-d', 'postgres', '-v', 'ON_ERROR_STOP=1', '--single-transaction'], input=migration))
        print('V24 successfully rehearsed against an isolated restore of the live database.')
    finally:
        run(['docker', 'stop', rehearsal])
elif mode == 'deploy':
    manifest = read('manifest.json')
    live = inspect('ai-gateway-standalone')
    assert live['Id'] == manifest['previousContainer'] or live['Image'] == manifest['image'], 'Live deployment changed'
    try:
        (out / 'database-cutover.dump').write_bytes(run(['docker', 'exec', 'ai-gateway-postgres', 'pg_dump',
                                                       '-U', 'ai_gateway', '-d', 'ai_gateway', '-Fc']))
        (out / 'migration-live.log').write_bytes(run(['docker', 'exec', '-i', 'ai-gateway-postgres', 'psql',
            '-U', 'ai_gateway', '-d', 'ai_gateway', '-v', 'ON_ERROR_STOP=1', '--single-transaction'],
            input=(root / 'src/main/resources' / resources[0]).read_bytes()))
        (out / 'deployment.log').write_bytes(run(compose('release.compose.json') + ['up', '-d', '--no-deps', '--no-build', 'gateway'], cwd=str(root)))
    except Exception:
        (out / 'rollback.log').write_bytes(run(compose('rollback.compose.json') + ['up', '-d', '--no-deps', '--no-build', 'gateway'], cwd=str(root)))
        raise
    print('V24 applied transactionally and gateway updated with the original runtime settings.')
elif mode == 'verify':
    manifest = read('manifest.json')
    live = inspect('ai-gateway-standalone')
    assert live['Image'] == manifest['image']
    assert runtime_signature(live) == runtime_signature(read('container-before.json')), 'Runtime configuration differs'
    assert run(['docker', 'exec', 'ai-gateway-standalone', 'sha256sum', '/app/app.jar']).decode().split()[0] == manifest['jarSha256']
    with urlopen('http://127.0.0.1:38008/actuator/health', timeout=10) as response:
        assert json.load(response)['status'] == 'UP'
    for path in ['/portal/', '/admin/']:
        with urlopen('http://127.0.0.1:38008' + path, timeout=10) as response:
            assert response.read() == (root / 'frontend/dist/index.html').read_bytes(), 'Deployed frontend differs'
    for name, before in read('other-containers-before.json').items():
        container = inspect(name)
        assert container['Id'] == before['id'] and container['State']['StartedAt'] == before['startedAt'], 'Unrelated container changed'
    schema = run(['docker', 'exec', 'ai-gateway-postgres', 'psql', '-U', 'ai_gateway', '-d', 'ai_gateway', '-tAc',
                  "SELECT data_type || ':' || character_maximum_length FROM information_schema.columns WHERE table_schema='public' AND table_name='model_square_content' AND column_name='icon_id'"]).decode().strip()
    assert schema == 'character varying:64', 'Icon schema missing'
    print('Health UP; icon schema, JAR, frontend and runtime settings verified; other containers unchanged.')
elif mode == 'rollback':
    (out / 'rollback.log').write_bytes(run(compose('rollback.compose.json') + ['up', '-d', '--no-deps', '--no-build', 'gateway'], cwd=str(root)))
    print('Previous gateway image restored with its exact runtime configuration.')
else:
    raise RuntimeError('Unknown stage')
