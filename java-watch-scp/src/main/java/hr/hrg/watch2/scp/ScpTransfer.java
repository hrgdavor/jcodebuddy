// SPDX-License-Identifier: LicenseRef-GPL-3.0-with-Commons-Clause
// Copyright (c) 2026 Davor Hrg
package hr.hrg.watch2.scp;

import com.jcraft.jsch.*;
import hr.hrg.watch2.core.ChecksumDatabase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import com.jcraft.jsch.agentproxy.AgentProxyException;
import com.jcraft.jsch.agentproxy.Connector;
import com.jcraft.jsch.agentproxy.ConnectorFactory;
import com.jcraft.jsch.agentproxy.RemoteIdentityRepository;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.Vector;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Matcher;

/**
 * Handles SCP file transfers to remote destinations using JSch.
 */
public class ScpTransfer {
    public static class RemoteFileInfo {
        public final long size;
        public final long mtime;

        public RemoteFileInfo(long size, long mtime) {
            this.size = size;
            this.mtime = mtime;
        }
    }
    private static final Logger logger = LoggerFactory.getLogger(ScpTransfer.class);
    public static final String REPLACE_ESCAPE_DOLLAR = Matcher.quoteReplacement("\\") + Matcher.quoteReplacement("$");

    private final String host;
    private final int port;
    private final String username;
    private final String password;
    private final String keyPath;
    private final String passphrase;
    private final boolean compress;
    private final String remoteBasePath;
    volatile Session session;
    private final Set<String> createdDirs = ConcurrentHashMap.newKeySet();
    AtomicInteger createdChannels = new AtomicInteger();
    AtomicInteger closedChannels = new AtomicInteger();

    public ScpTransfer(String host, int port, String username, String password, String keyPath, String passphrase,
            boolean compress, String remoteBasePath) {
        this.host = host;
        this.port = port;
        this.username = username;
        this.password = password;
        this.keyPath = keyPath;
        this.passphrase = passphrase;
        this.compress = compress;
        this.remoteBasePath = remoteBasePath.endsWith("/") ? remoteBasePath : remoteBasePath + "/";
    }

    public boolean transferFile(File localFile, String relativePath) {
        long fileSize = localFile.length();
        try (InputStream fis = new FileInputStream(localFile)) {
            return transferFile(fis, fileSize, relativePath);
        } catch (IOException e) {
            logger.error("Failed to transfer file: " + localFile.getAbsolutePath(), e);
            return false;
        }
    }

    private synchronized void checkSession() throws JSchException {
        if (session == null || !session.isConnected()) {
            if (session != null)
                logger.info("Reconnecting");
            JSch jsch = new JSch();
            if (keyPath != null && !keyPath.isEmpty()) {
                if (passphrase != null && !passphrase.isEmpty()) {
                    jsch.addIdentity(keyPath, passphrase);
                } else {
                    jsch.addIdentity(keyPath);
                }
            }

            // Attempt to use SSH Agent
            try {
                ConnectorFactory cf = ConnectorFactory.getDefault();
                Connector con = cf.createConnector();
                if (con != null) {
                    jsch.setIdentityRepository(new RemoteIdentityRepository(con));
                    logger.info("Using SSH Agent for authentication");
                }
            } catch (AgentProxyException e) {
                logger.debug("SSH Agent not available: {}", e.getMessage());
            }

            session = jsch.getSession(username, host, port);
            session.setConfig("StrictHostKeyChecking", "no");
            if (compress) {
                session.setConfig("compression.s2c", "zlib,none");
                session.setConfig("compression.c2s", "zlib,none");
            }
            if (password != null && !password.isEmpty()) {
                session.setPassword(password);
            }
            session.connect();
        }
    }

    public boolean transferFile(InputStream fis, final long fileSize, String relativePath) {
        ChannelSftp sftp = null;
        try {
            String remotePath = remoteBasePath + relativePath.replace('\\', '/');
            int idx = remotePath.lastIndexOf('/');
            String remoteDir = remotePath.substring(0, idx);

            createRemoteDirectory(remoteDir);

            sftp = (ChannelSftp) openChannel("sftp");
            sftp.connect();

            final long oneMB = 1024 * 1024;
            sftp.put(fis, remotePath, new SftpProgressMonitor() {
                long totalRead = 0;
                long lastProgress = 0;

                @Override
                public void init(int op, String src, String dest, long max) {
                }

                @Override
                public boolean count(long count) {
                    totalRead += count;
                    if (fileSize > oneMB && (totalRead - lastProgress >= oneMB)) {
                        logger.info("  Progress: {} / {} bytes ({}%)", totalRead, fileSize, (totalRead * 100) / fileSize);
                        lastProgress = totalRead;
                    }
                    return true;
                }

                @Override
                public void end() {
                }
            });

            return true;
        } catch (JSchException | SftpException | IOException e) {
            logger.error("Failed to transfer file: " + relativePath, e);
            return false;
        } finally {
            if (sftp != null)
                sftp.disconnect();
        }
    }

    public InputStream downloadFileStream(String remotePath) {
        ChannelSftp sftp = null;
        try {
            String fullRemotePath = remoteBasePath + remotePath.replace('\\', '/');
            sftp = (ChannelSftp) openChannel("sftp");
            sftp.connect();

            final InputStream in = sftp.get(fullRemotePath);
            final ChannelSftp finalSftp = sftp;

            return new InputStream() {
                @Override
                public int read() throws IOException {
                    return in.read();
                }

                @Override
                public int read(byte[] b, int off, int len) throws IOException {
                    return in.read(b, off, len);
                }

                @Override
                public void close() throws IOException {
                    try {
                        in.close();
                    } finally {
                        finalSftp.disconnect();
                    }
                }
            };
        } catch (JSchException | SftpException e) {
            logger.debug("Failed to open download stream: " + remotePath, e);
            if (sftp != null)
                sftp.disconnect();
            return null;
        }
    }

    public boolean downloadFile(String remotePath, File localFile) {
        ChannelSftp sftp = null;
        try {
            String fullRemotePath = remoteBasePath + remotePath.replace('\\', '/');
            sftp = (ChannelSftp) openChannel("sftp");
            sftp.connect();

            File parentDir = localFile.getParentFile();
            if (parentDir != null && !parentDir.exists())
                parentDir.mkdirs();

            final long oneMB = 1024 * 1024;
            final SftpATTRS attrs = sftp.stat(fullRemotePath);
            final long fileSize = attrs.getSize();

            sftp.get(fullRemotePath, localFile.getAbsolutePath(), new SftpProgressMonitor() {
                long totalRead = 0;
                long lastProgress = 0;

                @Override
                public void init(int op, String src, String dest, long max) {
                }

                @Override
                public boolean count(long count) {
                    totalRead += count;
                    if (fileSize > oneMB && (totalRead - lastProgress >= oneMB)) {
                        logger.info("  Download Progress: {} / {} bytes ({}%)", totalRead, fileSize,
                                (totalRead * 100) / fileSize);
                        lastProgress = totalRead;
                    }
                    return true;
                }

                @Override
                public void end() {
                }
            });

            return true;
        } catch (JSchException | SftpException e) {
            logger.debug("Failed to download file: " + remotePath, e);
            return false;
        } finally {
            if (sftp != null)
                sftp.disconnect();
        }
    }

    public RemoteFileInfo getRemoteFileInfo(String relativePath) {
        ChannelSftp sftp = null;
        try {
            String fullRemotePath = remoteBasePath + relativePath.replace('\\', '/');
            sftp = (ChannelSftp) openChannel("sftp");
            sftp.connect();

            SftpATTRS attrs = sftp.stat(fullRemotePath);
            return new RemoteFileInfo(attrs.getSize(), (long) attrs.getMTime() * 1000L);
        } catch (JSchException | SftpException e) {
            return null;
        } finally {
            if (sftp != null)
                sftp.disconnect();
        }
    }

    public boolean uploadDatabase(ChecksumDatabase db, String databaseFilename) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try {
            logger.info("Uploading database " + databaseFilename);
            db.save(out);
            byte[] bytes = out.toByteArray();
            return transferFile(new java.io.ByteArrayInputStream(bytes), bytes.length, databaseFilename);
        } catch (IOException e) {
            logger.error("Failed to serialize database", e);
            return false;
        }
    }

    public boolean downloadDatabase(ChecksumDatabase db, String databaseFilename) {
        try (InputStream in = downloadFileStream(databaseFilename)) {
            if (in == null)
                return false;
            db.load(in);
            return true;
        } catch (IOException e) {
            logger.debug("Failed to download database", e);
            return false;
        }
    }

    public List<String> listRemoteFilesRecursive(String relativePath) {
        List<String> files = new ArrayList<>();
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect();
            String fullPath = remoteBasePath + relativePath.replace('\\', '/');
            if (fullPath.endsWith("/"))
                fullPath = fullPath.substring(0, fullPath.length() - 1);
            listRecursive(sftp, fullPath, "", files);
        } catch (JSchException | SftpException e) {
            logger.error("Failed to list remote files: " + relativePath, e);
        } finally {
            if (sftp != null)
                sftp.disconnect();
        }
        return files;
    }

    @SuppressWarnings("unchecked")
    private void listRecursive(ChannelSftp sftp, String basePath, String currentRelPath, List<String> files)
            throws SftpException {
        String fullPath = currentRelPath.isEmpty() ? basePath : basePath + "/" + currentRelPath;
        Vector<ChannelSftp.LsEntry> entries = sftp.ls(fullPath);
        for (ChannelSftp.LsEntry entry : entries) {
            String name = entry.getFilename();
            if (name.equals(".") || name.equals(".."))
                continue;

            String relPath = currentRelPath.isEmpty() ? name : currentRelPath + "/" + name;
            if (entry.getAttrs().isDir()) {
                listRecursive(sftp, basePath, relPath, files);
            } else {
                files.add(relPath);
            }
        }
    }

    public boolean removeRemoteFile(String relativePath) {
        ChannelSftp sftp = null;
        try {
            sftp = (ChannelSftp) session.openChannel("sftp");
            sftp.connect();
            String fullPath = remoteBasePath + relativePath.replace('\\', '/');
            sftp.rm(fullPath);
            return true;
        } catch (JSchException | SftpException e) {
            logger.error("Failed to remove remote file: " + relativePath, e);
            return false;
        } finally {
            if (sftp != null)
                sftp.disconnect();
        }
    }

    private void createRemoteDirectory(String remoteDir) throws JSchException, IOException {
        if (createdDirs.contains(remoteDir))
            return;
        createdDirs.add(remoteDir);
        execCommandAndPrint("mkdir -p " + remoteDir);
    }

    public void execCommandAndPrint(String command) throws JSchException, IOException {
        ChannelExec channel = null;
        try {
            logger.info("Executing: {}", command);
            channel = (ChannelExec) openChannel("exec");
            channel.setCommand(command);

            InputStream in = channel.getInputStream();
            InputStream err = channel.getErrStream();

            connect(channel);

            byte[] tmp = new byte[1024];
            while (true) {
                while (in.available() > 0) {
                    int i = in.read(tmp, 0, 1024);
                    if (i < 0)
                        break;
                    System.out.print(new String(tmp, 0, i));
                }
                while (err.available() > 0) {
                    int i = err.read(tmp, 0, 1024);
                    if (i < 0)
                        break;
                    System.err.print(new String(tmp, 0, i));
                }
                if (channel.isClosed()) {
                    if (in.available() > 0 || err.available() > 0)
                        continue;
                    break;
                }
                try {
                    Thread.sleep(100);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } finally {
            disconnect(channel);
        }
    }

    private void connect(Channel channel) throws JSchException {
        channel.connect();
    }

    private void disconnect(Channel channel) {
        if (channel != null && channel.isConnected())
            channel.disconnect();
    }

    private synchronized Channel openChannel(String type) throws JSchException {
        checkSession();
        return session.openChannel(type);
    }

    private int checkAck(InputStream in) throws IOException {
        int b = in.read();
        if (b == 0)
            return 0;
        if (b == -1)
            return -1;
        if (b == 1 || b == 2) {
            StringBuilder sb = new StringBuilder();
            int c;
            while ((c = in.read()) != -1 && c != '\n')
                sb.append((char) c);
            logger.error("SCP error: {}", sb.toString());
        }
        return b;
    }
}
