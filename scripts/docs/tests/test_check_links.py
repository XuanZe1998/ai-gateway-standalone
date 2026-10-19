import importlib.util
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

spec = importlib.util.spec_from_file_location(
    "check_links", Path(__file__).resolve().parents[1] / "check-links.py"
)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)


class LinkCheckerTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(dir=Path.cwd())
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.page = self.root / "page.md"
        self.checker = module.LinkChecker(str(self.root), local_only=True)

    def write(self, content):
        self.page.write_text(content, encoding="utf-8")
        return self.checker.check_file(self.page)

    def test_fenced_code_and_inline_literals_are_not_links(self):
        result = self.write("```md\n[example](missing.md)\n```\n\n`[literal](missing.md)`")
        self.assertEqual(0, result["summary"]["total"])

    def test_real_missing_file_fails(self):
        result = self.write("[missing](missing.md)")
        self.assertEqual(1, result["summary"]["invalid"])

    def test_missing_images_are_checked(self):
        result = self.write("![diagram](missing.png)")
        self.assertEqual(1, result["summary"]["invalid"])

    def test_encoded_path_and_fragment_resolve_locally(self):
        (self.root / "usage report.md").touch()
        result = self.write("[report](usage%20report.md#summary)")
        self.assertEqual(1, result["summary"]["valid"])

    def test_reference_links_are_checked(self):
        result = self.write("[guide][reference]\n\n[reference]: missing.md")
        self.assertEqual(1, result["summary"]["invalid"])

    def test_local_only_never_requests_external_urls(self):
        with patch.object(self.checker, "check_external_link") as request:
            result = self.write("[external](https://example.com/)")
        request.assert_not_called()
        self.assertEqual(1, result["summary"]["skipped"])


if __name__ == "__main__":
    unittest.main()
