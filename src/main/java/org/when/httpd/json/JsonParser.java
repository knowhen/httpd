package org.when.httpd.json;

import org.when.httpd.json.parser.Parser;
import org.when.httpd.json.token.CharReader;
import org.when.httpd.json.token.TokenList;
import org.when.httpd.json.token.Tokenizer;

import java.io.IOException;
import java.io.StringReader;

/**
 * @author: when
 * @create: 2020-04-17  10:28
 **/
public class JsonParser {
    private Tokenizer tokenizer = new Tokenizer();

    private Parser parser = new Parser();

    public Object fromJSON(String json) throws IOException {
        CharReader charReader = new CharReader(new StringReader(json));
        TokenList tokens = tokenizer.tokenize(charReader);
        return parser.parse(tokens);
    }
}
