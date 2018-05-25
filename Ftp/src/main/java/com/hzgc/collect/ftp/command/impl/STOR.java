package com.hzgc.collect.ftp.command.impl;

import com.hzgc.collect.expand.receiver.Event;
import com.hzgc.collect.zk.subscribe.SubscribeInfo;
import com.hzgc.collect.expand.util.*;
import com.hzgc.collect.ftp.command.AbstractCommand;
import com.hzgc.collect.ftp.ftplet.*;
import com.hzgc.collect.ftp.impl.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.SocketException;

public class STOR extends AbstractCommand {
    private final Logger LOG = LoggerFactory.getLogger(STOR.class);

    public void execute(final FtpIoSession session,
                        final FtpServerContext context, final FtpRequest request)
            throws IOException, FtpException {

        try {

            // get state variable
            long skipLen = session.getFileOffset();

            // argument check
            String fileName = request.getArgument();
            if (fileName == null) {
                session
                        .write(LocalizedDataTransferFtpReply
                                .translate(
                                        session,
                                        request,
                                        context,
                                        FtpReply.REPLY_501_SYNTAX_ERROR_IN_PARAMETERS_OR_ARGUMENTS,
                                        "STOR", null, null));
                return;
            }

            // 24-10-2007 - added check if PORT or PASV is issued, see
            // https://issues.apache.org/jira/browse/FTPSERVER-110
            DataConnectionFactory connFactory = session.getDataConnection();
            if (connFactory instanceof IODataConnectionFactory) {
                InetAddress address = ((IODataConnectionFactory) connFactory)
                        .getInetAddress();
                if (address == null) {
                    session.write(new DefaultFtpReply(
                            FtpReply.REPLY_503_BAD_SEQUENCE_OF_COMMANDS,
                            "PORT or PASV must be issued first"));
                    return;
                }
            }

            // get filename
            FtpFile file = null;
            try {
                file = session.getFileSystemView().getFile(fileName);
            } catch (Exception ex) {
                LOG.debug("Exception getting file object", ex);
            }
            if (file == null) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        "STOR.invalid", fileName, null));
                return;
            }

            // get permission
            if (!file.isWritable()) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_550_REQUESTED_ACTION_NOT_TAKEN,
                        "STOR.permission", fileName, file));
                return;
            }

            // get data connection
            session.write(
                    LocalizedFtpReply.translate(session, request, context,
                            FtpReply.REPLY_150_FILE_STATUS_OKAY, "STOR",
                            fileName)).awaitUninterruptibly(10000);

            DataConnection dataConnection;
            try {
                dataConnection = session.getDataConnection().openConnection();
            } catch (Exception e) {
                LOG.debug("Exception getting the input data stream", e);
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_425_CANT_OPEN_DATA_CONNECTION, "STOR",
                        fileName, file));
                return;
            }

            // transfer data
            boolean failure = false;
            OutputStream outStream = null;
            long transSz = 0L;
            try {
                outStream = file.createOutputStream(skipLen);
                transSz = dataConnection.transferFromClient(session.getFtpletSession(), outStream);

                // attempt to close the output stream so that errors in
                // closing it will return an error to the client (FTPSERVER-119)
                if (outStream != null) {
                    outStream.close();
                }

                LOG.info("File uploaded {}", fileName);

                // notify the statistics component
                ServerFtpStatistics ftpStat = (ServerFtpStatistics) context
                        .getFtpStatistics();
                ftpStat.setUpload(session, file, transSz);

            } catch (SocketException ex) {
                LOG.debug("Socket exception during data transfer", ex);
                failure = true;
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_426_CONNECTION_CLOSED_TRANSFER_ABORTED,
                        "STOR", fileName, file));
            } catch (IOException ex) {
                LOG.debug("IOException during data transfer", ex);
                failure = true;
                session
                        .write(LocalizedDataTransferFtpReply
                                .translate(
                                        session,
                                        request,
                                        context,
                                        FtpReply.REPLY_551_REQUESTED_ACTION_ABORTED_PAGE_TYPE_UNKNOWN,
                                        "STOR", fileName, file));
            } finally {
                // make sure we really close the output stream
                if (outStream != null) {
                    outStream.close();
                }
                //此处获取到的路径是图片上传路径即/ipcid/xx/xx,不是文件系统的绝对路径
                fileName = file.getAbsolutePath();

                if (fileName.contains("unknown")) {
                    LOG.error(fileName + ":contain unknown ipcID, Not send to rocketMQ and Kafka!");
                } else {
                    //0为大图,大于1为小图
                    int faceNum = FtpPathParser.pcikSmallPicture(fileName);
                    if (fileName.contains(".jpg") && faceNum > 0) {

                        FtpPathMetaData metaData = FtpPathParser.getFtpPathMetaData(fileName);
                        if (CollectProperties.isFtpSubscribeSwitch()) {
                            if (SubscribeInfo.getIpcList().isEmpty()) {
                                if (SubscribeInfo.getIpcList().contains(metaData.getIpcid())) {
                                    sendMQAndReceive(file, metaData, context);
                                }
                            } else {
                                onlyReceive(file, metaData, context);
                            }
                        } else {
                            sendMQAndReceive(file, metaData, context);
                        }
                    }
                }
            }

            // if data transfer ok - send transfer complete message
            if (!failure) {
                session.write(LocalizedDataTransferFtpReply.translate(session, request, context,
                        FtpReply.REPLY_226_CLOSING_DATA_CONNECTION, "STOR",
                        fileName, file, transSz));

            }
        } finally {
            session.resetState();
            session.getDataConnection().closeDataConnection();
        }
    }

    private void sendMQAndReceive(FtpFile file, FtpPathMetaData metaData, FtpServerContext context) {
        //拼装ftpUrl (ftp://hostname/)
        String ftpHostNameUrl = FtpPathParser.ftpPath2HostNamepath(file.getAbsolutePath());
        String bigPicHostNameUrl = FtpPathParser.surlToBurl(ftpHostNameUrl);
        //获取ftpUrl (ftp://ip/)
        String ftpIpUrl = FtpPathParser.hostNameUrl2IpUrl(ftpHostNameUrl);
        Event event = new Event();
        event.setTimeStamp(metaData.getTimeStamp());
        event.setAbsolutePath(file.getFileAbsolutePa());
        event.setFtpHostNameUrlPath(ftpHostNameUrl);
        event.setFtpIpUrlPath(ftpIpUrl);
        event.setBigPicurl(bigPicHostNameUrl);
        event.setIpcId(metaData.getIpcid());
        event.setDate(metaData.getDate());
        event.setTimeSlot(metaData.getTimeslot());
        //发送到rocketMQ
        ProducerRocketMQ.getInstance().send(metaData.getIpcid(), metaData.getTimeStamp(), ftpIpUrl.getBytes());
        context.getScheduler().putData(event);
    }

    private void onlyReceive(FtpFile file, FtpPathMetaData metaData, FtpServerContext context) {
        //拼装ftpUrl (ftp://hostname/)
        String ftpHostNameUrl = FtpPathParser.ftpPath2HostNamepath(file.getAbsolutePath());
        String bigPicHostNameUrl = FtpPathParser.surlToBurl(ftpHostNameUrl);
        String ftpIpUrl = FtpPathParser.hostNameUrl2IpUrl(ftpHostNameUrl);
        Event event = new Event();
        event.setTimeStamp(metaData.getTimeStamp());
        event.setAbsolutePath(file.getFileAbsolutePa());
        event.setFtpHostNameUrlPath(ftpHostNameUrl);
        event.setFtpIpUrlPath(ftpIpUrl);
        event.setBigPicurl(bigPicHostNameUrl);
        event.setIpcId(metaData.getIpcid());
        event.setDate(metaData.getDate());
        event.setTimeSlot(metaData.getTimeslot());
        context.getScheduler().putData(event);
    }
}
