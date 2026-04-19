package org.saiku.service.util.security.authentication;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.Properties;

public class FileBackedPasswordProvider implements PasswordProvider {
    private final String password;

    public FileBackedPasswordProvider(File passwordPropertyFile, String passwordKey) {
        this.password = loadPasswordFromFile(passwordPropertyFile, passwordKey);
    }

    public String getPassword() {
        return password;
    }

    private String loadPasswordFromFile(File passwordPropertyFile, String passwordKey) {
        final Properties properties = new Properties();
        InputStream inputStream = null;

        try {
            inputStream = new FileInputStream(passwordPropertyFile);
            properties.load(inputStream);

            String password = properties.getProperty(passwordKey);

            if (password == null) {
                throw new RuntimeException("Did not find password key '" + passwordKey + "' in file "
                        + passwordPropertyFile.getAbsolutePath());
            }

            return password;
        } catch (IOException e) {
            throw new RuntimeException(
                    "Failed to load password from file: " + passwordPropertyFile.getAbsolutePath(), e);
        } finally {
            closeInputStream(inputStream);
        }
    }

    private void closeInputStream(InputStream is) {
        try {
            if (is != null) {
                is.close();
            }
        } catch (IOException e) {
            throw new RuntimeException("Failed to close input stream", e);
        }
    }
}
