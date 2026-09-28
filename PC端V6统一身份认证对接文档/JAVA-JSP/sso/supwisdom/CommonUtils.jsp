<%@ page language="java"
	import="java.net.*, java.io.*, java.nio.charset.Charset"
	contentType="text/html; charset=UTF-8" pageEncoding="UTF-8"%>
<%@ page import="java.security.SecureRandom" %>
<%@ page import="java.security.cert.X509Certificate" %>
<%@ page import="javax.net.ssl.*" %>
<%@ page import="java.security.cert.CertificateException" %>
<%@ include file="Constants.jsp"%>
<%!public static abstract class StringUtils {

		public static boolean isEmpty(CharSequence cs) {
			return cs == null || cs.length() == 0;
		}

		public static byte[] getBytes(final String string) {
			if (string == null) {
				return null;
			}
			return string.getBytes();
		}

		public static String newString(final byte[] bytes) {
			return bytes == null ? null : new String(bytes);
		}
	}%>
<%!public static abstract class IOUtils {

		private static final int EOF = -1;

		private static final int DEFAULT_BUFFER_SIZE = 1024 * 4;

		public static void close(URLConnection conn) {
			if (conn instanceof HttpURLConnection) {
				((HttpURLConnection) conn).disconnect();
			}
		}

		public static void closeQuietly(InputStream input) {
			closeQuietly((Closeable) input);
		}

		public static void closeQuietly(Closeable closeable) {
			try {
				if (closeable != null) {
					closeable.close();
				}
			} catch (IOException ioe) {
				// ignore
			}
		}

		public static String toString(InputStream input, Charset encoding)
				throws IOException {
			StringWriter sw = new StringWriter();
			copy(input, sw, encoding);
			return sw.toString();
		}

		public static void copy(InputStream input, Writer output,
				Charset encoding) throws IOException {
			encoding = encoding == null ? Charset.defaultCharset() : encoding;
			InputStreamReader in = new InputStreamReader(input, encoding);
			copy(in, output);
		}

		public static int copy(Reader input, Writer output) throws IOException {
			long count = copyLarge(input, output);
			if (count > Integer.MAX_VALUE) {
				return -1;
			}
			return (int) count;
		}

		public static long copyLarge(Reader input, Writer output)
				throws IOException {
			return copyLarge(input, output, new char[DEFAULT_BUFFER_SIZE]);
		}

		public static long copyLarge(Reader input, Writer output, char[] buffer)
				throws IOException {
			long count = 0;
			int n;
			while (EOF != (n = input.read(buffer))) {
				output.write(buffer, 0, n);
				count += n;
			}
			return count;
		}

		public static InputStream toInputStream(String input, Charset encoding) {
			return new ByteArrayInputStream(input.getBytes());
		}

	}%>
<%!public static abstract class Base64Utils {

		private static final byte[] ENC_TAB_BYTES = new byte[64];

		static {
			int j = 0;
			for (byte i = 'A'; i <= 'Z'; i++) {
				ENC_TAB_BYTES[j] = i;
				j++;
			}
			for (byte i = 'a'; i <= 'z'; i++) {
				ENC_TAB_BYTES[j] = i;
				j++;
			}
			for (byte i = '0'; i <= '9'; i++) {
				ENC_TAB_BYTES[j] = i;
				j++;
			}
			ENC_TAB_BYTES[j++] = '+';
			ENC_TAB_BYTES[j] = '/';
		}

		public static String encodeBase64Str(String str) {
			byte[] bytes = StringUtils.getBytes(str);
			return encodeBase64Str(bytes);
		}

		public static String encodeBase64Str(byte[] bytes) {
			byte[] base64Bytes = encodeBase64(bytes);
			return StringUtils.newString(base64Bytes);
		}

		public static byte[] encodeBase64(byte[] bytes) {
			byte[] base64Bytes;
			int modulus = bytes.length % 3;
			if (modulus == 0) {
				base64Bytes = new byte[(4 * bytes.length) / 3];
			} else {
				base64Bytes = new byte[4 * ((bytes.length / 3) + 1)];
			}
			int dataLength = (bytes.length - modulus);
			int a1;
			int a2;
			int a3;
			for (int i = 0, j = 0; i < dataLength; i += 3, j += 4) {
				a1 = bytes[i] & 0xff;
				a2 = bytes[i + 1] & 0xff;
				a3 = bytes[i + 2] & 0xff;
				base64Bytes[j] = ENC_TAB_BYTES[(a1 >>> 2) & 0x3f];
				base64Bytes[j + 1] = ENC_TAB_BYTES[((a1 << 4) | (a2 >>> 4)) & 0x3f];
				base64Bytes[j + 2] = ENC_TAB_BYTES[((a2 << 2) | (a3 >>> 6)) & 0x3f];
				base64Bytes[j + 3] = ENC_TAB_BYTES[a3 & 0x3f];
			}
			int b1;
			int b2;
			int b3;
			int d1;
			int d2;
			switch (modulus) {
			case 0:
				break;
			case 1:
				d1 = bytes[bytes.length - 1] & 0xff;
				b1 = (d1 >>> 2) & 0x3f;
				b2 = (d1 << 4) & 0x3f;
				base64Bytes[base64Bytes.length - 4] = ENC_TAB_BYTES[b1];
				base64Bytes[base64Bytes.length - 3] = ENC_TAB_BYTES[b2];
				base64Bytes[base64Bytes.length - 2] = '=';
				base64Bytes[base64Bytes.length - 1] = '=';
				break;
			case 2:
				d1 = bytes[bytes.length - 2] & 0xff;
				d2 = bytes[bytes.length - 1] & 0xff;
				b1 = (d1 >>> 2) & 0x3f;
				b2 = ((d1 << 4) | (d2 >>> 4)) & 0x3f;
				b3 = (d2 << 2) & 0x3f;
				base64Bytes[base64Bytes.length - 4] = ENC_TAB_BYTES[b1];
				base64Bytes[base64Bytes.length - 3] = ENC_TAB_BYTES[b2];
				base64Bytes[base64Bytes.length - 2] = ENC_TAB_BYTES[b3];
				base64Bytes[base64Bytes.length - 1] = '=';
				break;
			}
			return base64Bytes;
		}

		private static final byte[] DEC_TAB_BYTES = new byte[128];

		static {
			for (int i = 0; i < 128; i++) {
				DEC_TAB_BYTES[i] = (byte) -1;
			}
			for (int i = 'A'; i <= 'Z'; i++) {
				DEC_TAB_BYTES[i] = (byte) (i - 'A');
			}
			for (int i = 'a'; i <= 'z'; i++) {
				DEC_TAB_BYTES[i] = (byte) (i - 'a' + 26);
			}
			for (int i = '0'; i <= '9'; i++) {
				DEC_TAB_BYTES[i] = (byte) (i - '0' + 52);
			}
			DEC_TAB_BYTES['+'] = 62;
			DEC_TAB_BYTES['/'] = 63;
		}

		public static String decodeBase64Str(String data) {
			byte[] base64Bytes = StringUtils.getBytes(data);
			return decodeBase64Str(base64Bytes);
		}

		public static String decodeBase64Str(byte[] base64Bytes) {
			byte[] bytes = decodeBase64(base64Bytes);
			return StringUtils.newString(bytes);
		}

		public static byte[] decodeBase64(byte[] base64Bytes) {
			byte[] bytes;
			byte b1;
			byte b2;
			byte b3;
			byte b4;
			base64Bytes = discardNonBase64Bytes(base64Bytes);
			if (base64Bytes[base64Bytes.length - 2] == '=') {
				bytes = new byte[(((base64Bytes.length / 4) - 1) * 3) + 1];
			} else if (base64Bytes[base64Bytes.length - 1] == '=') {
				bytes = new byte[(((base64Bytes.length / 4) - 1) * 3) + 2];
			} else {
				bytes = new byte[((base64Bytes.length / 4) * 3)];
			}
			for (int i = 0, j = 0; i < (base64Bytes.length - 4); i += 4, j += 3) {
				b1 = DEC_TAB_BYTES[base64Bytes[i]];
				b2 = DEC_TAB_BYTES[base64Bytes[i + 1]];
				b3 = DEC_TAB_BYTES[base64Bytes[i + 2]];
				b4 = DEC_TAB_BYTES[base64Bytes[i + 3]];
				bytes[j] = (byte) ((b1 << 2) | (b2 >> 4));
				bytes[j + 1] = (byte) ((b2 << 4) | (b3 >> 2));
				bytes[j + 2] = (byte) ((b3 << 6) | b4);
			}
			if (base64Bytes[base64Bytes.length - 2] == '=') {
				b1 = DEC_TAB_BYTES[base64Bytes[base64Bytes.length - 4]];
				b2 = DEC_TAB_BYTES[base64Bytes[base64Bytes.length - 3]];
				bytes[bytes.length - 1] = (byte) ((b1 << 2) | (b2 >> 4));
			} else if (base64Bytes[base64Bytes.length - 1] == '=') {
				b1 = DEC_TAB_BYTES[base64Bytes[base64Bytes.length - 4]];
				b2 = DEC_TAB_BYTES[base64Bytes[base64Bytes.length - 3]];
				b3 = DEC_TAB_BYTES[base64Bytes[base64Bytes.length - 2]];
				bytes[bytes.length - 2] = (byte) ((b1 << 2) | (b2 >> 4));
				bytes[bytes.length - 1] = (byte) ((b2 << 4) | (b3 >> 2));
			} else {
				b1 = DEC_TAB_BYTES[base64Bytes[base64Bytes.length - 4]];
				b2 = DEC_TAB_BYTES[base64Bytes[base64Bytes.length - 3]];
				b3 = DEC_TAB_BYTES[base64Bytes[base64Bytes.length - 2]];
				b4 = DEC_TAB_BYTES[base64Bytes[base64Bytes.length - 1]];
				bytes[bytes.length - 3] = (byte) ((b1 << 2) | (b2 >> 4));
				bytes[bytes.length - 2] = (byte) ((b2 << 4) | (b3 >> 2));
				bytes[bytes.length - 1] = (byte) ((b3 << 6) | b4);
			}
			return bytes;
		}

		private static byte[] discardNonBase64Bytes(byte[] datas) {
			byte[] temp = new byte[datas.length];
			int bytesCopied = 0;
			for (byte data : datas) {
				if (isValidBase64Byte(data)) {
					temp[bytesCopied++] = data;
				}
			}
			byte[] newData = new byte[bytesCopied];
			System.arraycopy(temp, 0, newData, 0, bytesCopied);
			return newData;
		}

		private static boolean isValidBase64Byte(byte b) {
			if (b == '=') {
				return true;
			} else if ((b < 0) || (b >= 128)) {
				return false;
			} else if (DEC_TAB_BYTES[b] == -1) {
				return false;
			}
			return true;
		}
	}%>
<%!public static abstract class BooleanUtils {

		public static boolean toBoolean(final String str) {
			return toBooleanObject(str) == Boolean.TRUE;
		}

		public static Boolean toBooleanObject(final String str) {
			if (str.equals("true")) {
				return Boolean.TRUE;
			}
			switch (str.length()) {
			case 1: {
				final char ch0 = str.charAt(0);
				if (ch0 == 'y' || ch0 == 'Y' || ch0 == 't' || ch0 == 'T') {
					return Boolean.TRUE;
				}
				if (ch0 == 'n' || ch0 == 'N' || ch0 == 'f' || ch0 == 'F') {
					return Boolean.FALSE;
				}
				break;
			}
			case 2: {
				final char ch0 = str.charAt(0);
				final char ch1 = str.charAt(1);
				if ((ch0 == 'o' || ch0 == 'O') && (ch1 == 'n' || ch1 == 'N')) {
					return Boolean.TRUE;
				}
				if ((ch0 == 'n' || ch0 == 'N') && (ch1 == 'o' || ch1 == 'O')) {
					return Boolean.FALSE;
				}
				break;
			}
			case 3: {
				final char ch0 = str.charAt(0);
				final char ch1 = str.charAt(1);
				final char ch2 = str.charAt(2);
				if ((ch0 == 'y' || ch0 == 'Y') && (ch1 == 'e' || ch1 == 'E')
						&& (ch2 == 's' || ch2 == 'S')) {
					return Boolean.TRUE;
				}
				if ((ch0 == 'o' || ch0 == 'O') && (ch1 == 'f' || ch1 == 'F')
						&& (ch2 == 'f' || ch2 == 'F')) {
					return Boolean.FALSE;
				}
				break;
			}
			case 4: {
				final char ch0 = str.charAt(0);
				final char ch1 = str.charAt(1);
				final char ch2 = str.charAt(2);
				final char ch3 = str.charAt(3);
				if ((ch0 == 't' || ch0 == 'T') && (ch1 == 'r' || ch1 == 'R')
						&& (ch2 == 'u' || ch2 == 'U')
						&& (ch3 == 'e' || ch3 == 'E')) {
					return Boolean.TRUE;
				}
				break;
			}
			case 5: {
				final char ch0 = str.charAt(0);
				final char ch1 = str.charAt(1);
				final char ch2 = str.charAt(2);
				final char ch3 = str.charAt(3);
				final char ch4 = str.charAt(4);
				if ((ch0 == 'f' || ch0 == 'F') && (ch1 == 'a' || ch1 == 'A')
						&& (ch2 == 'l' || ch2 == 'L')
						&& (ch3 == 's' || ch3 == 'S')
						&& (ch4 == 'e' || ch4 == 'E')) {
					return Boolean.FALSE;
				}
				break;
			}
			default:
				break;
			}
			return null;
		}
	}%>
<%!public static abstract class HttpRequestUtils {

		public static String doGet(String urlStr) throws IOException {
			URL url = new URL(urlStr);
			InputStream in = null;
			HttpURLConnection conn = null;
			try {
				trustEveryone();
				conn = (HttpURLConnection) url.openConnection();
				conn.setConnectTimeout(Constants.CONNECT_TIMEOUT);
				conn.connect();
				in = conn.getInputStream();
				return IOUtils.toString(in, Constants.UTF_8);
			} finally {
				IOUtils.close(conn);
				IOUtils.closeQuietly(in);
			}
		}

	public static void trustEveryone() {

		try {
			HttpsURLConnection.setDefaultHostnameVerifier(new HostnameVerifier() {
				public boolean verify(String hostname, SSLSession session) {
					return true;
				}
			});

			SSLContext context = SSLContext.getInstance("TLS");
			context.init(null, new X509TrustManager[]{new X509TrustManager() {
				public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				}

				public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
				}

				public X509Certificate[] getAcceptedIssuers() {
					return new X509Certificate[0];
				}
			}}, new SecureRandom());
			HttpsURLConnection.setDefaultSSLSocketFactory(context.getSocketFactory());
		} catch (Exception e) {
			e.printStackTrace();
		}
	}

	}%>

