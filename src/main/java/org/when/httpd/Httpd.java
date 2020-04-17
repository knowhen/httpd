package org.when.httpd;

import org.when.httpd.exception.InvalidHeaderException;

import java.io.FileNotFoundException;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.channels.*;
import java.time.Instant;
import java.util.*;

import static org.when.httpd.HttpStatus.*;

/**
 * @author: when
 * @create: 2020-04-16  16:08
 **/
public class Httpd {
    private static final int DEFAULT_PORT = 8080;
    private static final int DEFAULT_BUFFER_SIZE = 4096;
    private static final String INDEX_PAGE = "index.html";
    private static final String STATIC_RESOURCE_DIR = "static";
    private static final String META_RESOURCE_DIR_PREFIX = "/meta/";
    private static final String KEY_VALUE_SEPARATOR = ":";
    private static final String CRLF = "\r\n";

    private int port;

    public Httpd() {
        this(DEFAULT_PORT);
    }

    public Httpd(int port) {
        this.port = port;
    }

    public void start() throws IOException {
        // initialize ServerSocketChannel
        ServerSocketChannel ssc = ServerSocketChannel.open();
        ssc.socket().bind(new InetSocketAddress("localhost", port));
        ssc.configureBlocking(false);

        // create selector
        Selector selector = Selector.open();

        // register event
        ssc.register(selector, SelectionKey.OP_ACCEPT);

        while (true) {
            int readyNum = selector.select();
            if (readyNum == 0) {
                continue;
            }

            Set<SelectionKey> selectionKeys = selector.selectedKeys();
            Iterator<SelectionKey> iterator = selectionKeys.iterator();
            while (iterator.hasNext()) {
                SelectionKey selectionKey = iterator.next();
                iterator.remove();

                if (selectionKey.isAcceptable()) {
                    SocketChannel socketChannel = ssc.accept();
                    socketChannel.configureBlocking(false);
                    socketChannel.register(selector, SelectionKey.OP_READ);
                } else if (selectionKey.isReadable()) {
                    // handle request
                    request(selectionKey);
                    selectionKey.interestOps(SelectionKey.OP_WRITE);
                } else if (selectionKey.isWritable()) {
                    // respond request
                    response(selectionKey);
                }
            }
        }
    }

    private void request(SelectionKey selectionKey) throws IOException {
        // read request header from channel
        SocketChannel channel = (SocketChannel) selectionKey.channel();
        ByteBuffer buffer = ByteBuffer.allocate(DEFAULT_BUFFER_SIZE);
        channel.read(buffer);

        buffer.flip();
        byte[] bytes = new byte[buffer.limit()];
        buffer.get(bytes);
        String headerStr = new String(bytes);
        try {
            // parse request header
            Headers headers = parseHeader(headerStr);
            // put headers into selectionKey
            selectionKey.attach(Optional.of(headers));
        } catch (InvalidHeaderException e) {
            selectionKey.attach(Optional.empty());
        }
    }

    private Headers parseHeader(String headerStr) {
        if (Objects.isNull(headerStr) || headerStr.isEmpty()) {
            throw new InvalidHeaderException();
        }

        // parse first line of header
        int index = headerStr.indexOf(CRLF);
        if (index == -1) {
            throw new InvalidHeaderException();
        }

        Headers headers = new Headers();
        String firstLine = headerStr.substring(0, index);
        String[] parts = firstLine.split(" ");

        /**
         * first line of header must be made up of three parts,
         * METHOD PATH and VERSION.
         * eg: GET /index.html HTTP/1.1
         */
        if (parts.length < 3) {
            throw new InvalidHeaderException();
        }

        headers.setMethod(parts[0]);
        headers.setPath(parts[1]);
        headers.setVersion(parts[2]);

        // parse which part the headers belongs to
        parts = headerStr.split(CRLF);
        for (String part : parts) {
            index = part.indexOf(KEY_VALUE_SEPARATOR);
            if (index == -1) {
                continue;
            }
            String key = part.substring(0, index);
            if (index == -1 || index + 1 >= part.length()) {
                headers.put(key, "");
                continue;
            }
            String value = part.substring(index + 1);
            headers.put(key, value);
        }

        return headers;
    }

    @SuppressWarnings("unchecked")
    private void response(SelectionKey selectionKey) throws IOException {
        SocketChannel channel = (SocketChannel) selectionKey.channel();
        // 从 selectionKey 中取出请求头对象
//        Headers h = (Headers) selectionKey.attachment();
//        Optional<Headers> op = Optional.of(h);
        Optional<Headers> op = (Optional<Headers>) selectionKey.attachment();

        // 处理无效请求，返回 400 错误
        if (!op.isPresent()) {
            handleBadRequest(channel);
            channel.close();
            return;
        }

        String ip = channel.getRemoteAddress().toString().replace("/", "");
        Headers headers = op.get();
        // 如果请求 /meta/ 路径下的资源，则认为是非法请求，返回 403 错误
        if (headers.getPath().startsWith(META_RESOURCE_DIR_PREFIX)) {
            handleForbidden(channel);
            channel.close();
            log(ip, headers, FORBIDDEN.getCode());
            return;
        }

        try {
            handleOK(channel, headers.getPath());
            log(ip, headers, OK.getCode());
        } catch (FileNotFoundException e) {
            // 文件未发现，返回 404 错误
            handleNotFound(channel);
            log(ip, headers, NOT_FOUND.getCode());
        } catch (Exception e) {
            // 其他异常，返回 500 错误
            handleInternalServerError(channel);
            log(ip, headers, INTERNAL_SERVER_ERROR.getCode());
        } finally {
            channel.close();
        }
    }

    private void handleOK(SocketChannel channel, String path) throws IOException {
        ResponseHeaders headers = new ResponseHeaders(OK.getCode());

        // 读取文件
        ByteBuffer bodyBuffer = readFile(path);
        // 设置响应头
        headers.setContentLength(bodyBuffer.capacity());
        headers.setContentType(ContentTypeUtils.getContentType(getExtension(path)));
        ByteBuffer headerBuffer = ByteBuffer.wrap(headers.toString().getBytes());

        // 将响应头和资源数据一同返回
        channel.write(new ByteBuffer[]{headerBuffer, bodyBuffer});
    }

    private void handleNotFound(SocketChannel channel) {
        try {
            handleError(channel, NOT_FOUND.getCode());
        } catch (Exception e) {
            handleInternalServerError(channel);
        }
    }

    private void handleBadRequest(SocketChannel channel) {
        try {
            handleError(channel, BAD_REQUEST.getCode());
        } catch (Exception e) {
            handleInternalServerError(channel);
        }
    }

    private void handleForbidden(SocketChannel channel) {
        try {
            handleError(channel, FORBIDDEN.getCode());
        } catch (Exception e) {
            handleInternalServerError(channel);
        }
    }

    private void handleInternalServerError(SocketChannel channel) {
        try {
            handleError(channel, INTERNAL_SERVER_ERROR.getCode());
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void handleError(SocketChannel channel, int statusCode) throws IOException {
        ResponseHeaders headers = new ResponseHeaders(statusCode);
        // 读取文件
        ByteBuffer bodyBuffer = readFile(String.format("/%d.html", statusCode));
        // 设置响应头
        headers.setContentLength(bodyBuffer.capacity());
        headers.setContentType(ContentTypeUtils.getContentType("html"));
        ByteBuffer headerBuffer = ByteBuffer.wrap(headers.toString().getBytes());

        // 将响应头和资源数据一同返回
        channel.write(new ByteBuffer[]{headerBuffer, bodyBuffer});
    }

    private ByteBuffer readFile(String path) throws IOException {
        path = STATIC_RESOURCE_DIR + (path.endsWith("/") ? path + INDEX_PAGE : path);
        RandomAccessFile raf = new RandomAccessFile(path, "r");
        FileChannel channel = raf.getChannel();

        ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
        channel.read(buffer);

        buffer.flip();
        return buffer;
    }

    private String getExtension(String path) {
        if (path.endsWith("/")) {
            return "html";
        }

        String finename = path.substring(path.lastIndexOf("/") + 1);
        int index = finename.lastIndexOf(".");
        return index == -1 ? "*" : finename.substring(index + 1);
    }

    private void log(String ip, Headers headers, int code) {
        // ip [date] "Method path version" code user-agent
        String dateStr = Date.from(Instant.now()).toString();
        String msg = String.format("%s [%s] \"%s %s %s\" %d %s",
                ip, dateStr, headers.getMethod(), headers.getPath(), headers.getVersion(), code, headers.get("User" +
                        "-Agent"));
        System.out.println(msg);
    }

    public static void main(String[] args) throws IOException {
        new Httpd().start();
    }
}
