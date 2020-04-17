package org.when.httpd;


import org.when.httpd.json.JsonParser;
import org.when.httpd.json.model.JsonObject;

import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;

/**
 * @author: when
 * @create: 2020-04-17  10:27
 **/
public class ContentTypeUtils {
    private static JsonObject jsonObject;

    private static final String JSON_PATH = "static/meta/content-type.json";

    static {
        JsonParser jsonParser = new JsonParser();
        try {
            jsonObject = (JsonObject) jsonParser.fromJSON(readFile());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String readFile() {
        try {
            RandomAccessFile raf = new RandomAccessFile(JSON_PATH, "r");
            FileChannel channel = raf.getChannel();

            ByteBuffer buffer = ByteBuffer.allocate((int) channel.size());
            buffer.clear();
            channel.read(buffer);
            buffer.flip();
            return new String(buffer.array());
        } catch (Exception e) {
            e.printStackTrace();
        }

        return null;
    }

    public static String getContentType(String ext) {
        return (String) jsonObject.get(ext);
    }
}
