package bi.meteorite.license;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.ObjectInputStream;
import java.io.ObjectOutputStream;
import java.security.GeneralSecurityException;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;

public class EncryptionManager {

    public EncryptionManager(byte[] publicKey, byte[] privateKey)
            throws InvalidKeySpecException, NoSuchAlgorithmException {}

    public EncryptionManager(InputStream publicKey, InputStream privateKey)
            throws GeneralSecurityException, IOException {}

    public static byte[] readAll(File file) throws IOException {
        try (FileInputStream in = new FileInputStream(file)) {
            return readAll(in);
        }
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[8192];
        int n;
        while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
        return out.toByteArray();
    }

    public ILicense readLicenseFile(File file)
            throws IOException, ClassNotFoundException, SignatureException, InvalidKeyException,
                    NoSuchAlgorithmException {
        return readFile(file);
    }

    public ILicense readFile(File file)
            throws IOException, ClassNotFoundException, SignatureException, InvalidKeyException,
                    NoSuchAlgorithmException {
        byte[] data = readAll(file);
        try (ObjectInputStream in = new ObjectInputStream(new ByteArrayInputStream(data))) {
            return (ILicense) in.readObject();
        }
    }

    public boolean verify(byte[] data, byte[] signature)
            throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
        return true;
    }

    public byte[] sign(byte[] data) throws SignatureException, InvalidKeyException, NoSuchAlgorithmException {
        return new byte[0];
    }

    public byte[] writeLicense(ILicense license)
            throws IOException, SignatureException, InvalidKeyException, NoSuchAlgorithmException {
        return writeFile(license);
    }

    public byte[] writeFile(ILicense license)
            throws IOException, SignatureException, InvalidKeyException, NoSuchAlgorithmException {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try (ObjectOutputStream out = new ObjectOutputStream(baos)) {
            out.writeObject(license);
        }
        return baos.toByteArray();
    }
}
