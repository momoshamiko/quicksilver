// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

/** A {@link PackSource} over a pack zip file. The zip stays open until {@link #close()}. */
final class ZipPackSource extends IndexedPackSource {
	/** Hard cap on one decompressed zip entry (64 MiB) to defend against zip bombs. */
	private static final int MAX_ENTRY_BYTES = 64 * 1024 * 1024;
	private final ZipFile zip;
	/** Normalized pack path to the entry name exactly as the zip stores it (which may use {@code \} or a {@code ./} prefix). */
	private final Map<String, String> storedNames;

	ZipPackSource(Path zipFile) throws IOException {
		this(zipFile, openAndScan(zipFile));
	}

	private ZipPackSource(Path zipFile, Scan scan) throws IOException {
		super(name(zipFile), scan.entries, scan.zip);
		this.storedNames = scan.storedNames;
		this.zip = scan.zip;
	}

	private static String name(Path zipFile) {
		return zipFile.getFileName() == null ? zipFile.toString() : zipFile.getFileName().toString();
	}

	private record Scan(ZipFile zip, Map<String, Boolean> entries, Map<String, String> storedNames) {
	}

	private static ZipFile openZip(Path zipFile) throws IOException {
		try {
			return new ZipFile(zipFile.toFile(), StandardCharsets.UTF_8);
		} catch (IllegalArgumentException | ZipException badUtf8) {
			try {
				return new ZipFile(zipFile.toFile(), Charset.forName("CP437"));
			} catch (IOException | RuntimeException cp437Failed) {
				badUtf8.addSuppressed(cp437Failed);
				throw badUtf8;
			}
		}
	}

	private static Scan openAndScan(Path zipFile) throws IOException {
		ZipFile z = openZip(zipFile);
		try {
			return scanZip(z);
		} catch (RuntimeException ex) {
			try {
				z.close();
			} catch (IOException suppressed) {
				ex.addSuppressed(suppressed);
			}
			if (ex instanceof IllegalArgumentException) {
				// Retry entry scan with CP437 if ZipFile opened lazily and failed UTF-8 decoding while iterating entries.
				ZipFile cp437 = new ZipFile(zipFile.toFile(), Charset.forName("CP437"));
				try {
					return scanZip(cp437);
				} catch (RuntimeException ex2) {
					try {
						cp437.close();
					} catch (IOException suppressed) {
						ex2.addSuppressed(suppressed);
					}
					throw ex2;
				}
			}
			throw ex;
		}
	}

	private static Scan scanZip(ZipFile z) {
		Map<String, Boolean> entries = new HashMap<>();
		Map<String, String> stored = new HashMap<>();
		Enumeration<? extends ZipEntry> e = z.entries();
		while (e.hasMoreElements()) {
			ZipEntry entry = e.nextElement();
			String raw = entry.getName();
			String p = raw.replace('\\', '/');
			boolean dir = entry.isDirectory();
			while (p.startsWith("/")) p = p.substring(1);
			while (p.startsWith("./")) p = p.substring(2);
			while (p.endsWith("/")) {
				p = p.substring(0, p.length() - 1);
				dir = true;
			}
			if (p.isEmpty() || p.equals(".")) continue;
			entries.put(p, dir);
			if (!dir) stored.put(p, raw);
		}
		return new Scan(z, entries, stored);
	}

	@Override
	protected byte[] readEntry(String packPath) throws IOException {
		String stored = storedNames.get(packPath);
		if (stored == null) return null;
		ZipEntry e = zip.getEntry(stored);
		if (e == null || e.isDirectory()) return null;
		if (e.getSize() > MAX_ENTRY_BYTES) {
			throw new IOException("zip entry exceeds 64 MiB limit: " + packPath);
		}
		try (InputStream in = zip.getInputStream(e)) {
			ByteArrayOutputStream out = new ByteArrayOutputStream(e.getSize() > 0 ? (int) e.getSize() : 4096);
			byte[] buf = new byte[8192];
			int total = 0;
			int n;
			while ((n = in.read(buf)) >= 0) {
				total += n;
				if (total > MAX_ENTRY_BYTES) {
					throw new IOException("zip entry exceeds 64 MiB limit: " + packPath);
				}
				out.write(buf, 0, n);
			}
			return out.toByteArray();
		}
	}

	@Override
	public void close() throws IOException {
		zip.close();
	}
}
