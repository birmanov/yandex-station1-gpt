import java.io.*;
import java.net.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

public final class SessionPersistenceCheck {
  static final List<StationRemote> opened = new ArrayList<StationRemote>();
  static void require(boolean value, String message) { if (!value) throw new AssertionError(message); }
  static StationRemote open(File root) throws Exception {
    StationRemote r = new StationRemote(root, "127.0.0.1", 0);
    opened.add(r); return r;
  }
  static StationRemote.Request cookie(String id) {
    StationRemote.Request r = new StationRemote.Request();
    r.headers.put("cookie", StationRemote.COOKIE + "=" + id); return r;
  }
  static String http(StationRemote r, String path, String id, String csrf, String body, String origin) throws Exception {
    ServerSocket listener = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
    Socket client = new Socket("127.0.0.1", listener.getLocalPort());
    client.setSoTimeout(5000);
    Socket accepted = listener.accept(); accepted.setSoTimeout(5000);
    byte[] payload = body.getBytes("UTF-8");
    String headers = "POST " + path + " HTTP/1.1\r\nHost: 127.0.0.1:0\r\nCookie: " + StationRemote.COOKIE + "=" + id
      + "\r\nContent-Type: application/x-www-form-urlencoded\r\nContent-Length: " + payload.length + "\r\n";
    if (csrf != null) headers += "X-Remote-CSRF: " + csrf + "\r\n";
    if (origin != null) headers += "Origin: " + origin + "\r\n";
    client.getOutputStream().write((headers + "\r\n").getBytes("US-ASCII"));
    client.getOutputStream().write(payload); client.getOutputStream().flush();
    r.handle(accepted);
    ByteArrayOutputStream out = new ByteArrayOutputStream(); byte[] data = new byte[2048]; int n;
    while ((n = client.getInputStream().read(data)) != -1) out.write(data, 0, n);
    client.close(); listener.close(); return out.toString("UTF-8");
  }
  static void clean(File f) {
    File[] children = f.listFiles(); if (children != null) for (File c : children) clean(c);
    f.delete();
  }
  static void test() throws Exception {
    File root = new File("/data/local/tmp/remote-session-check-" + System.nanoTime());
    require(root.mkdirs(), "fixture directory");
    try {
      StationRemote.writeFile(new File(root, "index.html"), "test".getBytes("UTF-8"));
      Properties config = new Properties(); String salt = StationRemote.token();
      config.setProperty("salt", salt); config.setProperty("passwordHash", StationRemote.hash("fixture-only-pass", salt));
      ByteArrayOutputStream raw = new ByteArrayOutputStream(); config.store(raw, "isolated regression fixture, not user credentials");
      StationRemote.writeFile(new File(root, "auth.properties"), raw.toByteArray());
      StationRemote first = open(root); StationRemote.Session s = first.newSession();
      StationRemote restarted = open(root);
      StationRemote.Session recovered = restarted.session(cookie(s.id));
      require(recovered != null, "RED: login is forgotten after server restart");
      require(s.csrf.equals(recovered.csrf), "CSRF must survive restart unchanged");
      require(restarted.sessionCookie(recovered).contains("Max-Age=" + TimeUnit.DAYS.toSeconds(30)), "remember login for 30 days");
      require(recovered.expires - System.currentTimeMillis() > TimeUnit.DAYS.toMillis(29), "session retention window");
      require(restarted.session(cookie("0000000000000000000000000000000000000000000000000000000000000000")) == null, "forged cookie rejected");
      require(http(restarted, "/api/logout", s.id, null, "", null).startsWith("HTTP/1.1 403"), "missing CSRF rejected");
      require(http(restarted, "/api/logout", s.id, s.csrf, "", "http://untrusted.invalid").startsWith("HTTP/1.1 403"), "foreign origin rejected");
      require(restarted.session(cookie(s.id)) != null, "rejected requests must not log out");
      require(http(restarted, "/api/logout", s.id, s.csrf, "", null).startsWith("HTTP/1.1 200"), "logout succeeds");
      require(restarted.session(cookie(s.id)) == null, "logout revokes current session");
      StationRemote afterLogout = open(root);
      require(afterLogout.session(cookie(s.id)) == null, "logout stays revoked after restart");
      StationRemote.Session changed = afterLogout.newSession();
      require(http(afterLogout, "/api/password", changed.id, changed.csrf,
        "current=fixture-only-pass&password=fixture-only-changed", null).startsWith("HTTP/1.1 200"), "password change succeeds");
      require(afterLogout.session(cookie(changed.id)) == null, "password change revokes old session");
      StationRemote afterPassword = open(root);
      require(afterPassword.session(cookie(changed.id)) == null, "old session remains revoked after password restart");
      require(afterPassword.sessions.size() == 1, "fresh password session survives restart");
      StationRemote.Session fresh = afterPassword.sessions.values().iterator().next();
      afterPassword.setPassword("fixture-only-third");
      StationRemote changedGeneration = open(root);
      require(changedGeneration.session(cookie(fresh.id)) == null, "changed credentials invalidate old disk sessions even across interruption");
      StationRemote.writeFile(new File(root, "sessions.properties"), "broken=\\uZZZZ".getBytes("US-ASCII"));
      StationRemote corrupted = open(root);
      require(corrupted.sessions.isEmpty(), "corrupt session storage fails closed");
      System.out.println("PASS restart persistence; 30-day cookie; CSRF and origin; logout; password rotation; corruption fails closed");
    } finally {
      for (StationRemote r : opened) { r.clock.shutdownNow(); r.workers.shutdownNow(); }
      clean(root);
    }
  }
  static void processCheck(String mode, File root) throws Exception {
    require(root.getAbsolutePath().startsWith("/data/local/tmp/remote-session-process-"), "isolated fixture path");
    try {
      if ("seed".equals(mode)) {
        require(root.mkdirs(), "fresh process fixture");
        StationRemote.writeFile(new File(root, "index.html"), "test".getBytes("UTF-8"));
        Properties config = new Properties(); String salt = StationRemote.token();
        config.setProperty("salt", salt); config.setProperty("passwordHash", StationRemote.hash("fixture-process-only", salt));
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(); config.store(bytes, "isolated process fixture");
        StationRemote.writeFile(new File(root, "auth.properties"), bytes.toByteArray());
        StationRemote r = open(root); StationRemote.Session s = r.newSession();
        StationRemote.writeFile(new File(root, "fixture-cookie"), (s.id + "\n" + s.csrf).getBytes("US-ASCII"));
        System.out.println("PASS seed session durably saved; process exiting");
      } else if ("recover".equals(mode)) {
        String[] saved = new String(StationRemote.readFile(new File(root, "fixture-cookie"), 256), "US-ASCII").split("\n");
        StationRemote r = open(root); StationRemote.Session s = r.session(cookie(saved[0]));
        require(s != null && saved[1].equals(s.csrf), "session and CSRF must survive full process exit");
        String answer = http(r, "/api/command", s.id, s.csrf, "action=ping&id=restart-test", null);
        require(answer.startsWith("HTTP/1.1 200") && !answer.contains("\"error\""), "authenticated command works after process restart");
        System.out.println("PASS session and CSRF survive full process exit; authenticated HTTP command works");
      } else throw new IllegalArgumentException("unknown process mode");
    } finally {
      for (StationRemote r : opened) { r.clock.shutdownNow(); r.workers.shutdownNow(); }
      if ("recover".equals(mode)) clean(root);
    }
  }
  // Called only by the test-build scheduler seam, never production source.
  static void loginBoundary(StationRemote r) throws Exception { }
  static File fixture(String name) throws Exception {
    File root = new File("/data/local/tmp/remote-session-" + name + "-" + System.nanoTime());
    require(root.mkdirs(), "fresh isolated fixture");
    StationRemote.writeFile(new File(root, "index.html"), "test".getBytes("UTF-8"));
    Properties p = new Properties(); String salt = StationRemote.token();
    p.setProperty("salt", salt); p.setProperty("passwordHash", StationRemote.hash("fixture-only-pass", salt));
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); p.store(bytes, "isolated review fixture");
    StationRemote.writeFile(new File(root, "auth.properties"), bytes.toByteArray());
    return root;
  }
  static void stoppedAtMonitorOrFinished(Thread t) throws Exception {
    long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (t.isAlive() && t.getState() != Thread.State.BLOCKED && System.nanoTime() < end) Thread.yield();
    require(!t.isAlive() || t.getState() == Thread.State.BLOCKED, "worker reaches controlled monitor boundary");
  }
  static void joined(Thread t) throws Exception {
    t.join(10000); require(!t.isAlive(), "fixture worker must terminate");
  }
  static void closeOpened() throws Exception {
    for (StationRemote r : opened) {
      r.clock.shutdownNow(); r.workers.shutdownNow();
      require(r.clock.awaitTermination(5, TimeUnit.SECONDS), "clock stopped");
      require(r.workers.awaitTermination(5, TimeUnit.SECONDS), "workers stopped");
    }
  }
  static void rotationRace() throws Exception {
    File root = fixture("rotation"); final StationRemote r = open(root);
    final Throwable[] error = new Throwable[1]; Thread rotation = null;
    try {
      StationRemote.Session old = r.newSession(); final String generation = r.config.getProperty("salt");
      rotation = new Thread(new Runnable() { public void run() {
        try { r.setPassword("fixture-only-changed"); } catch (Throwable t) { error[0] = t; }
      } }, "fixture-rotation");
      synchronized (r.sessionLock) {
        rotation.start(); stoppedAtMonitorOrFinished(rotation);
        require(generation.equals(r.config.getProperty("salt")),
          "RED rotation must not publish new generation while old sessions can be persisted");
        r.saveSessions();
      }
      joined(rotation); if (error[0] != null) throw new AssertionError(error[0]);
      r.newSession();
      require(open(root).session(cookie(old.id)) == null, "rotation cannot rebind old sessions to new credentials");
      System.out.println("PASS rotation serialized with concurrent persistence; old session not rebound");
    } finally { if (rotation != null) joined(rotation); closeOpened(); clean(root); }
  }
  public static void main(String[] args) {
    try {
      if (args.length == 1 && "rotation".equals(args[0])) rotationRace();
      else if (args.length == 2) processCheck(args[0], new File(args[1])); else test();
    } catch (Throwable t) { t.printStackTrace(System.out); System.exit(1); }
  }
}
