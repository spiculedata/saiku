/*
 * Copyright (c) 2012 David Stites, Patrik Dufresne and others.
 * 
 * You may distribute under the terms of either the MIT License, the Apache
 * License 2.0 or the Simplified BSD License, as specified in the README file.
 * 
 * Contributors:
 *     David Stites - initial API and implementation
 *     Patrik Dufresne - refactoring
 */
package org.saiku.service.license;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.security.InvalidKeyException;
import java.security.KeyFactory;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.SignatureException;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;

/**
 * This class is used to manage the encryption of the license. It's used to
 * encrypt, sign and validate using a public or private key.
 *
 * @author Patrik Dufresne
 */
public class EncryptionManager {

  private static final int SIZE = 2048;

  /**
   * Single instance of the utility class.
   */

  private PublicKey publicKey;

  /**
   * Our private key.
   */
  private PrivateKey privateKey;

  /**
   * Create a new encryption manager.
   *
   * @param publicKey  the public key (can't be null).
   * @param privateKey the private key (null if not available).
   * @throws NoSuchAlgorithmException     if no Provider supports
   *                                                    RSA
   * @throws InvalidKeySpecException if the given key specification
   *                                                    is inappropriate for
   *                                                    this key factory to
   *                                                    produce a public key.
   */
  public EncryptionManager(byte[] publicKey, byte[] privateKey)
      throws NoSuchAlgorithmException, InvalidKeySpecException {
    if (publicKey == null) {
      throw new NullPointerException("publicKey");
    }

    X509EncodedKeySpec spec = new X509EncodedKeySpec(publicKey);
    KeyFactory kf = KeyFactory.getInstance("RSA");
    this.publicKey = kf.generatePublic(spec);

    if (privateKey != null) {
      PKCS8EncodedKeySpec privateSpec = new PKCS8EncodedKeySpec(
          privateKey);
      KeyFactory privateKeyFactory = KeyFactory.getInstance("RSA");
      this.privateKey = privateKeyFactory.generatePrivate(privateSpec);
    }

  }

  /**
   * This function is used to read a stream.
   *
   * @param input the input stream
   * @return the data read from the stream
   * @throws IOException
   */
  public static byte[] readAll(InputStream input) throws IOException {
    // Read the content of the file and store it in a byte array.
    ByteArrayOutputStream out = new ByteArrayOutputStream(SIZE);
    byte[] buf = new byte[SIZE];
    int size;
    while ((size = input.read(buf)) != -1) {
      out.write(buf, 0, size);
    }
    return out.toByteArray();
  }

  /**
   * This function maybe used to read the public and/or private key from a
   * file.
   *
   * @param file the file to read
   * @return the file data
   * @throws IOException if the file does not exist, or if the first
   *                             byte cannot be read for any reason
   */
  public static byte[] readAll(File file) throws IOException {
    InputStream input = new FileInputStream(file);
    try {
      return readAll(input);
    } finally {
      input.close();
    }
  }

  /**
   * Use to check if the given data matches the given signature.
   *
   * @param data the data
   * @param sig  the signature associated with the data.
   * @throws NoSuchAlgorithmException if the algorithm SHA1withRSA
   *                                                is not supported.
   * @throws NoSuchProviderException
   * @throws InvalidKeyException      if the key is invalid.
   * @throws SignatureException       if this signature algorithm
   *                                                is unable to process the
   *                                                input data
   */
  public boolean verify(byte[] data, byte[] sig)
      throws NoSuchAlgorithmException, InvalidKeyException,
      SignatureException {

    // Initialize the signing algorithm with our public key
    Signature rsaSignature = Signature.getInstance("SHA1withRSA");
    rsaSignature.initVerify(publicKey);

    // Update the signature algorithm with the data.
    rsaSignature.update(data);

    // Validate the signature
    return rsaSignature.verify(sig);

  }

  /**
   * Sign the given input stream data. The signature is append to the output
   * stream.
   *
   * @param data the the data to be signed.
   * @return the signature for the given data.
   * @throws NoSuchAlgorithmException if no Provider supports a
   *                                                Signature implementation for
   *                                                SHA1withRSA.
   * @throws InvalidKeyException      if the private key is
   *                                                invalid.
   * @throws SignatureException       if this signature algorithm
   *                                                is unable to process the
   *                                                input data provided.
   * @throws UnsupportedOperationException          if the private key was not
   *                                                providedin the constructor.
   */
  public byte[] sign(byte[] data) throws NoSuchAlgorithmException,
      InvalidKeyException, SignatureException {
    if (privateKey == null) {
      throw new UnsupportedOperationException(
          "Can't sign when the private key is not available.");
    }

    // Initialize the signing algorithm with our private key
    Signature rsaSignature = Signature.getInstance("SHA1withRSA");
    rsaSignature.initSign(privateKey);
    rsaSignature.update(data);

    // Generate the signature.
    return rsaSignature.sign();

  }
}
