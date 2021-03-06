/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.nifi.processors.standard;

import java.io.File;
import java.io.IOException;
import java.nio.file.Paths;
import java.security.Security;
import java.util.Collection;

import org.apache.commons.codec.binary.Hex;
import org.apache.nifi.components.ValidationResult;
import org.apache.nifi.processors.standard.util.PasswordBasedEncryptor;
import org.apache.nifi.security.util.EncryptionMethod;
import org.apache.nifi.security.util.KeyDerivationFunction;
import org.apache.nifi.util.MockFlowFile;
import org.apache.nifi.util.MockProcessContext;
import org.apache.nifi.util.TestRunner;
import org.apache.nifi.util.TestRunners;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.junit.Assert;
import org.junit.Assume;
import org.junit.Before;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class TestEncryptContent {

    private static final Logger logger = LoggerFactory.getLogger(TestEncryptContent.class);

    @Before
    public void setUp() {
        Security.addProvider(new BouncyCastleProvider());
    }

    @Test
    public void testRoundTrip() throws IOException {
        final TestRunner testRunner = TestRunners.newTestRunner(new EncryptContent());
        testRunner.setProperty(EncryptContent.PASSWORD, "Hello, World!");

        for (final EncryptionMethod method : EncryptionMethod.values()) {
            if (method.isUnlimitedStrength()) {
                continue;   // cannot test unlimited strength in unit tests because it's not enabled by the JVM by default.
            }
            testRunner.setProperty(EncryptContent.ENCRYPTION_ALGORITHM, method.name());
            testRunner.setProperty(EncryptContent.MODE, EncryptContent.ENCRYPT_MODE);

            testRunner.enqueue(Paths.get("src/test/resources/hello.txt"));
            testRunner.clearTransferState();
            testRunner.run();

            testRunner.assertAllFlowFilesTransferred(EncryptContent.REL_SUCCESS, 1);

            MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(EncryptContent.REL_SUCCESS).get(0);
            testRunner.assertQueueEmpty();

            testRunner.setProperty(EncryptContent.MODE, EncryptContent.DECRYPT_MODE);
            testRunner.enqueue(flowFile);
            testRunner.clearTransferState();
            testRunner.run();
            testRunner.assertAllFlowFilesTransferred(EncryptContent.REL_SUCCESS, 1);

            logger.info("Successfully decrypted {}", method.name());

            flowFile = testRunner.getFlowFilesForRelationship(EncryptContent.REL_SUCCESS).get(0);
            flowFile.assertContentEquals(new File("src/test/resources/hello.txt"));
        }
    }

    @Test
    public void testShouldDetermineMaxKeySizeForAlgorithms() throws IOException {
        // Arrange
        final String AES_ALGORITHM = EncryptionMethod.MD5_256AES.getAlgorithm();
        final String DES_ALGORITHM = EncryptionMethod.MD5_DES.getAlgorithm();

        final int AES_MAX_LENGTH = PasswordBasedEncryptor.supportsUnlimitedStrength() ? Integer.MAX_VALUE : 128;
        final int DES_MAX_LENGTH = PasswordBasedEncryptor.supportsUnlimitedStrength() ? Integer.MAX_VALUE : 64;

        // Act
        int determinedAESMaxLength = PasswordBasedEncryptor.getMaxAllowedKeyLength(AES_ALGORITHM);
        int determinedTDESMaxLength = PasswordBasedEncryptor.getMaxAllowedKeyLength(DES_ALGORITHM);

        // Assert
        assert determinedAESMaxLength == AES_MAX_LENGTH;
        assert determinedTDESMaxLength == DES_MAX_LENGTH;
    }

    @Test
    public void testShouldDecryptOpenSSLRawSalted() throws IOException {
        // Arrange
        Assume.assumeTrue("Test is being skipped due to this JVM lacking JCE Unlimited Strength Jurisdiction Policy file.",
                PasswordBasedEncryptor.supportsUnlimitedStrength());

        final TestRunner testRunner = TestRunners.newTestRunner(new EncryptContent());

        final String password = "thisIsABadPassword";
        final EncryptionMethod method = EncryptionMethod.MD5_256AES;
        final KeyDerivationFunction kdf = KeyDerivationFunction.OPENSSL_EVP_BYTES_TO_KEY;

        testRunner.setProperty(EncryptContent.PASSWORD, password);
        testRunner.setProperty(EncryptContent.KEY_DERIVATION_FUNCTION, kdf.name());
        testRunner.setProperty(EncryptContent.ENCRYPTION_ALGORITHM, method.name());
        testRunner.setProperty(EncryptContent.MODE, EncryptContent.DECRYPT_MODE);

        // Act
        testRunner.enqueue(Paths.get("src/test/resources/TestEncryptContent/salted_raw.enc"));
        testRunner.clearTransferState();
        testRunner.run();

        // Assert
        testRunner.assertAllFlowFilesTransferred(EncryptContent.REL_SUCCESS, 1);
        testRunner.assertQueueEmpty();

        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(EncryptContent.REL_SUCCESS).get(0);
        logger.info("Decrypted contents (hex): {}", Hex.encodeHexString(flowFile.toByteArray()));
        logger.info("Decrypted contents: {}", new String(flowFile.toByteArray(), "UTF-8"));

        // Assert
        flowFile.assertContentEquals(new File("src/test/resources/TestEncryptContent/plain.txt"));
    }

    @Test
    public void testShouldDecryptOpenSSLRawUnsalted() throws IOException {
        // Arrange
        Assume.assumeTrue("Test is being skipped due to this JVM lacking JCE Unlimited Strength Jurisdiction Policy file.",
                PasswordBasedEncryptor.supportsUnlimitedStrength());

        final TestRunner testRunner = TestRunners.newTestRunner(new EncryptContent());

        final String password = "thisIsABadPassword";
        final EncryptionMethod method = EncryptionMethod.MD5_256AES;
        final KeyDerivationFunction kdf = KeyDerivationFunction.OPENSSL_EVP_BYTES_TO_KEY;

        testRunner.setProperty(EncryptContent.PASSWORD, password);
        testRunner.setProperty(EncryptContent.KEY_DERIVATION_FUNCTION, kdf.name());
        testRunner.setProperty(EncryptContent.ENCRYPTION_ALGORITHM, method.name());
        testRunner.setProperty(EncryptContent.MODE, EncryptContent.DECRYPT_MODE);

        // Act
        testRunner.enqueue(Paths.get("src/test/resources/TestEncryptContent/unsalted_raw.enc"));
        testRunner.clearTransferState();
        testRunner.run();

        // Assert
        testRunner.assertAllFlowFilesTransferred(EncryptContent.REL_SUCCESS, 1);
        testRunner.assertQueueEmpty();

        MockFlowFile flowFile = testRunner.getFlowFilesForRelationship(EncryptContent.REL_SUCCESS).get(0);
        logger.info("Decrypted contents (hex): {}", Hex.encodeHexString(flowFile.toByteArray()));
        logger.info("Decrypted contents: {}", new String(flowFile.toByteArray(), "UTF-8"));

        // Assert
        flowFile.assertContentEquals(new File("src/test/resources/TestEncryptContent/plain.txt"));
    }

    @Test
    public void testDecryptShouldDefaultToLegacyKDF() throws IOException {
        // Arrange
        final TestRunner testRunner = TestRunners.newTestRunner(new EncryptContent());

        // Assert
        Assert.assertEquals("Decrypt should default to Legacy KDF", testRunner.getProcessor().getPropertyDescriptor(EncryptContent.KEY_DERIVATION_FUNCTION
                .getName()).getDefaultValue(), KeyDerivationFunction.NIFI_LEGACY.name());
    }

    @Test
    public void testDecryptSmallerThanSaltSize() {
        final TestRunner runner = TestRunners.newTestRunner(EncryptContent.class);
        runner.setProperty(EncryptContent.PASSWORD, "Hello, World!");
        runner.setProperty(EncryptContent.MODE, EncryptContent.DECRYPT_MODE);
        runner.enqueue(new byte[4]);
        runner.run();
        runner.assertAllFlowFilesTransferred(EncryptContent.REL_FAILURE, 1);
    }

    @Test
    public void testPGPDecrypt() throws IOException {
        final TestRunner runner = TestRunners.newTestRunner(EncryptContent.class);
        runner.setProperty(EncryptContent.MODE, EncryptContent.DECRYPT_MODE);
        runner.setProperty(EncryptContent.ENCRYPTION_ALGORITHM, EncryptionMethod.PGP_ASCII_ARMOR.name());
        runner.setProperty(EncryptContent.PASSWORD, "Hello, World!");

        runner.enqueue(Paths.get("src/test/resources/TestEncryptContent/text.txt.asc"));
        runner.run();

        runner.assertAllFlowFilesTransferred(EncryptContent.REL_SUCCESS, 1);
        final MockFlowFile flowFile = runner.getFlowFilesForRelationship(EncryptContent.REL_SUCCESS).get(0);
        flowFile.assertContentEquals(Paths.get("src/test/resources/TestEncryptContent/text.txt"));
    }

    @Test
    public void testValidation() {
        final TestRunner runner = TestRunners.newTestRunner(EncryptContent.class);
        Collection<ValidationResult> results;
        MockProcessContext pc;

        runner.enqueue(new byte[0]);
        pc = (MockProcessContext) runner.getProcessContext();
        results = pc.validate();
        Assert.assertEquals(1, results.size());
        for (final ValidationResult vr : results) {
            Assert.assertTrue(vr.toString()
                    .contains(EncryptContent.PASSWORD.getDisplayName() + " is required when using algorithm"));
        }

        runner.enqueue(new byte[0]);
        runner.setProperty(EncryptContent.ENCRYPTION_ALGORITHM, EncryptionMethod.MD5_256AES.name());
        runner.setProperty(EncryptContent.PASSWORD, "ThisIsAPasswordThatIsLongerThanSixteenCharacters");
        pc = (MockProcessContext) runner.getProcessContext();
        results = pc.validate();
        if (!PasswordBasedEncryptor.supportsUnlimitedStrength()) {
            Assert.assertEquals(1, results.size());
            for (final ValidationResult vr : results) {
                Assert.assertTrue(
                        "Did not successfully catch validation error of a long password in a non-JCE Unlimited Strength environment",
                        vr.toString().contains("Password length greater than " + PasswordBasedEncryptor.getMaxAllowedKeyLength(EncryptionMethod.MD5_256AES.getAlgorithm())
                                + " bits is not supported by this JVM due to lacking JCE Unlimited Strength Jurisdiction Policy files."));
            }
        } else {
            Assert.assertEquals(0, results.size());
        }
        runner.removeProperty(EncryptContent.PASSWORD);

        runner.setProperty(EncryptContent.ENCRYPTION_ALGORITHM, EncryptionMethod.PGP.name());
        runner.setProperty(EncryptContent.PUBLIC_KEYRING, "src/test/resources/TestEncryptContent/text.txt");
        runner.enqueue(new byte[0]);
        pc = (MockProcessContext) runner.getProcessContext();
        results = pc.validate();
        Assert.assertEquals(1, results.size());
        for (final ValidationResult vr : results) {
            Assert.assertTrue(vr.toString().contains(
                    " encryption without a " + EncryptContent.PASSWORD.getDisplayName() + " requires both "
                            + EncryptContent.PUBLIC_KEYRING.getDisplayName() + " and "
                            + EncryptContent.PUBLIC_KEY_USERID.getDisplayName()));
        }

        runner.setProperty(EncryptContent.PUBLIC_KEY_USERID, "USERID");
        runner.enqueue(new byte[0]);
        pc = (MockProcessContext) runner.getProcessContext();
        results = pc.validate();
        Assert.assertEquals(1, results.size());
        for (final ValidationResult vr : results) {
            Assert.assertTrue(vr.toString().contains("does not contain user id USERID"));
        }

        runner.removeProperty(EncryptContent.PUBLIC_KEYRING);
        runner.removeProperty(EncryptContent.PUBLIC_KEY_USERID);

        runner.setProperty(EncryptContent.MODE, EncryptContent.DECRYPT_MODE);
        runner.setProperty(EncryptContent.PRIVATE_KEYRING, "src/test/resources/TestEncryptContent/text.txt");
        runner.enqueue(new byte[0]);
        pc = (MockProcessContext) runner.getProcessContext();
        results = pc.validate();
        Assert.assertEquals(1, results.size());
        for (final ValidationResult vr : results) {
            Assert.assertTrue(vr.toString().contains(
                    " decryption without a " + EncryptContent.PASSWORD.getDisplayName() + " requires both "
                            + EncryptContent.PRIVATE_KEYRING.getDisplayName() + " and "
                            + EncryptContent.PRIVATE_KEYRING_PASSPHRASE.getDisplayName()));

        }

        runner.setProperty(EncryptContent.PRIVATE_KEYRING_PASSPHRASE, "PASSWORD");
        runner.enqueue(new byte[0]);
        pc = (MockProcessContext) runner.getProcessContext();
        results = pc.validate();
        Assert.assertEquals(1, results.size());
        for (final ValidationResult vr : results) {
            Assert.assertTrue(vr.toString().contains(
                    " could not be opened with the provided " + EncryptContent.PRIVATE_KEYRING_PASSPHRASE.getDisplayName()));

        }
    }
}
