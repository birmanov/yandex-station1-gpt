import java.io.*;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.Collection;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;

/**
 * Optional CA bundle for Android 6, whose system store predates some current roots. Without the
 * file the platform default trust is used.
 */
public final class TrustStore {
  private TrustStore() { }

  /** Returns null when {@code pem} does not exist; otherwise a factory trusting only its certificates. */
  public static SSLSocketFactory load(File pem) throws IOException {
    if (!pem.isFile()) return null;
    FileInputStream in = new FileInputStream(pem);
    try {
      Collection<? extends Certificate> certs =
          CertificateFactory.getInstance("X.509").generateCertificates(in);
      if (certs.isEmpty()) throw new IOException("No certificates in " + pem.getName());
      KeyStore store = KeyStore.getInstance(KeyStore.getDefaultType());
      store.load(null, null);
      int i = 0;
      for (Certificate c : certs) store.setCertificateEntry("ca" + (i++), c);
      TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
      tmf.init(store);
      SSLContext context = SSLContext.getInstance("TLS");
      context.init(null, tmf.getTrustManagers(), null);
      return context.getSocketFactory();
    } catch (GeneralSecurityException e) {
      throw new IOException("Cannot load " + pem.getName() + ": " + e.getClass().getSimpleName());
    } finally {
      in.close();
    }
  }
}
