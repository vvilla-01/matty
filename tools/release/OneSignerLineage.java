// Writes a SigningCertificateLineage that holds a single signer: apksigner's command line can only
// make lineages of two or more signers (rotate), but its v3.2 hybrid signing needs a lineage on
// both hybrid signers. Usage: OneSignerLineage <keystore.p12> <alias> <password-file> <out-lineage>
import com.android.apksig.SigningCertificateLineage;
import java.io.*;
import java.nio.file.*;
import java.security.*;
import java.security.cert.X509Certificate;

public class OneSignerLineage {
    public static void main(String[] a) throws Exception {
        char[] pw = Files.readAllLines(Path.of(a[2])).get(0).toCharArray();
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = new FileInputStream(a[0])) { ks.load(in, pw); }
        PrivateKey key = (PrivateKey) ks.getKey(a[1], pw);
        X509Certificate cert = (X509Certificate) ks.getCertificate(a[1]);
        java.util.Arrays.fill(pw, '\0');
        SigningCertificateLineage lineage = new SigningCertificateLineage.Builder(
                new SigningCertificateLineage.SignerConfig.Builder(key, cert).build()).build();
        lineage.writeToFile(new File(a[3]));
        System.out.println("lineage of " + lineage.size() + " signer(s): " + cert.getSubjectX500Principal());
    }
}
