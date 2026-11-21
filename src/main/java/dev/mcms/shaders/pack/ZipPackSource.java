// Quicksilver: original code (ADR 0006), Apache-2.0.
package dev.mcms.shaders.pack;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/** A {@link PackSource} over a pack zip file. The zip stays open until {@link #close()}. */
final class ZipPackSource extends IndexedPackSource {
	private final ZipFile zip;
	/** Normalized pack path to the entry name exactly as the zip stores it (which may use {@code \} or a {@code ./} prefix). */
	private final Map<String, String> storedNames;

	ZipPackSource(Path zipFile) throws IOException {
		this(zipFile, scan(zipFile));
	}

	private ZipPackSource(Path zipFile, Scan scan) throws IOException {
		super(name(zipFile), scan.entries);
		this.storedNames = scan.storedNames;
		this.zip = new ZipFile(zipFile.toFile());
	}

	private static String name(Path zipFile) {
		return zipFile.getFileName() == null ? zipFile.toString() : zipFile.getFileName().toString();
	}

	private record Scan(Map<String, Boolean> entries, Map<String, String> storedNames) {
	}

	private static Scan scan(Path zipFile) throws IOException {
		Map<String, Boolean> entries = new HashMap<>();
		Map<String, String> stored = new HashMap<>();
		try (ZipFile z = new ZipFile(zipFile.toFile())) {
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
		}
		return new Scan(entries, stored);
	}

	@Override
	protected byte[] readEntry(String packPath) throws IOException {
		String stored = storedNames.get(packPath);
		if (stored == null) return null;
		ZipEntry e = zip.getEntry(stored);
		if (e == null || e.isDirectory()) return null;
		try (InputStream in = zip.getInputStream(e)) {
			return in.readAllBytes();
		}
	}

	@Override
	public void close() throws IOException {
		zip.close();
	}
}
