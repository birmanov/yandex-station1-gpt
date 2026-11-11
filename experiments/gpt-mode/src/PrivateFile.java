import java.io.*;

/** Owner-only files for secrets: every permission change is verified, writes are atomic. */
public final class PrivateFile {
  private PrivateFile() { }

  /** Removes all access for others and verifies the result; throws so the caller aborts the write. */
  public static void restrict(File f) throws IOException {
    // Windows is only the PC test host: NTFS has no POSIX bits and setReadable(false) always
    // reports failure there. The device (Android/Linux) takes the strict path below.
    if (File.separatorChar == '\\') return;
    boolean ok =
        f.setReadable(false, false)
            & f.setWritable(false, false)
            & f.setExecutable(false, false)
            & f.setReadable(true, true)
            & f.setWritable(true, true)
            & (!f.isDirectory() || f.setExecutable(true, true));
    if (!ok || !f.canRead() || !f.canWrite())
      throw new IOException("Cannot restrict permissions of " + f.getName());
  }

  /** Writes {@code data} to {@code target} via a restricted temp file and rename; temp removed on error. */
  public static void writeAtomic(File target, byte[] data) throws IOException {
    File next = new File(target.getParentFile(), target.getName() + ".new");
    next.delete();
    if (!next.createNewFile()) throw new IOException("Cannot create " + next.getName());
    try {
      restrict(next);
      FileOutputStream out = new FileOutputStream(next);
      try {
        out.write(data);
        out.getFD().sync();
      } finally {
        out.close();
      }
      if (target.exists() && !target.delete()) throw new IOException("Cannot replace " + target.getName());
      if (!next.renameTo(target)) throw new IOException("Cannot rename to " + target.getName());
    } catch (IOException e) {
      next.delete();
      throw e;
    }
  }
}
