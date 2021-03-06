/**
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
package org.apache.nutch.protocol.http;

// JDK imports
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PushbackInputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.SocketTimeoutException;
import java.net.URL;

// Nutch imports
import org.apache.nutch.crawl.CrawlDatum;
import org.apache.nutch.metadata.Metadata;
import org.apache.nutch.metadata.Nutch;
import org.apache.nutch.metadata.SpellCheckedMetadata;
import org.apache.nutch.net.protocols.HttpDateFormat;
import org.apache.nutch.net.protocols.Response;
import org.apache.nutch.protocol.ProtocolException;
import org.apache.nutch.protocol.http.api.HttpBase;
import org.apache.nutch.protocol.http.api.HttpException;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.SSLSocket;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;
import java.util.Date;


/** An HTTP response. */
public class HttpResponse implements Response {
 
  private HttpBase http; 
  private URL url;
  private String orig;
  private String base;
  private byte[] content;
  private int code;
  private Metadata headers = new SpellCheckedMetadata();
  private StringBuffer verbatimResponseHeaders = new StringBuffer();
  private final String CRLF = "\r\n";
  private int throughputData[] = new int[60];
  private int throughputDataPos = -1;
  protected long fetchStarted = -1;


  protected enum Scheme {
    HTTP,
    HTTPS,
  }

  public HttpResponse(HttpBase http, URL url, CrawlDatum datum)
    throws ProtocolException, IOException {

    this.http = http;
    this.url = url;
    this.orig = url.toString();
    this.base = url.toString();
    this.fetchStarted = new Date().getTime() / 1000;
    Scheme scheme = null;

    if ("http".equals(url.getProtocol())) {
      scheme = Scheme.HTTP;
    } else if ("https".equals(url.getProtocol())) {
      scheme = Scheme.HTTPS;
    } else {
      throw new HttpException("Unknown scheme (not http/https) for url:" + url);
    }

    if (Http.LOG.isTraceEnabled()) {
      Http.LOG.trace("fetching " + url);
    }

    String path = "".equals(url.getFile()) ? "/" : url.getFile();

    // some servers will redirect a request with a host line like
    // "Host: <hostname>:80" to "http://<hpstname>/<orig_path>"- they
    // don't want the :80...

    String host = url.getHost();
    int port;
    String portString;
    if (url.getPort() == -1) {
      if (scheme == Scheme.HTTP) {
        port = 80;
      } else {
        port = 443;
      }
      portString= "";
    } else {
      port= url.getPort();
      portString= ":" + port;
    }
    Socket socket = null;

    try {
      socket = new Socket();                    // create the socket
      socket.setSoTimeout(http.getTimeout());

       // connect
      String sockHost = http.useProxy() ? http.getProxyHost() : host;
      int sockPort = http.useProxy() ? http.getProxyPort() : port;
      InetSocketAddress sockAddr = new InetSocketAddress(sockHost, sockPort);
      socket.connect(sockAddr, http.getTimeout());

      if (scheme == Scheme.HTTPS) {
        SSLSocketFactory factory = (SSLSocketFactory)SSLSocketFactory.getDefault();
        SSLSocket sslsocket = (SSLSocket)factory.createSocket(socket, sockHost, sockPort, true);
        sslsocket.setUseClientMode(true);
        String[] protocols = { "TLSv1", "SSLv3" };
        sslsocket.setEnabledProtocols(protocols);
        sslsocket.startHandshake();
        socket = sslsocket;
      }

      // make request
      OutputStream req = socket.getOutputStream();

      StringBuffer reqStr = new StringBuffer("GET ");
      if (http.useProxy()) {
      	reqStr.append(url.getProtocol()+"://"+host+portString+path);
      } else {
      	reqStr.append(path);
      }

      reqStr.append(" HTTP/1.0\r\n");

      reqStr.append("Host: ");
      reqStr.append(host);
      reqStr.append(portString);
      reqStr.append("\r\n");

      reqStr.append("Accept-Encoding: x-gzip, gzip, deflate\r\n");

      String userAgent = http.getUserAgent();
      if ((userAgent == null) || (userAgent.length() == 0)) {
        if (Http.LOG.isErrorEnabled()) { Http.LOG.error("User-agent is not set!"); }
      } else {
        reqStr.append("User-Agent: ");
        reqStr.append(userAgent);
        reqStr.append("\r\n");
      }

      reqStr.append("Accept-Language: ");
      reqStr.append(this.http.getAcceptLanguage());
      reqStr.append("\r\n");

      reqStr.append("Accept: ");
      reqStr.append(this.http.getAccept());
      reqStr.append("\r\n");

      if (datum.getModifiedTime() > 0) {
        reqStr.append("If-Modified-Since: " + HttpDateFormat.toString(datum.getModifiedTime()));
        reqStr.append("\r\n");
      }
      reqStr.append("\r\n");

      headers.set(Nutch.FETCH_DEST_IP_KEY, sockAddr.getAddress().getHostAddress());
      headers.set(Nutch.FETCH_REQUEST_VERBATIM_KEY, reqStr.toString());

      byte[] reqBytes= reqStr.toString().getBytes();

      req.write(reqBytes);
      req.flush();
        
      PushbackInputStream in =                  // process response
        new PushbackInputStream(
          new BufferedInputStream(socket.getInputStream(), Http.BUFFER_SIZE), 
          Http.BUFFER_SIZE) ;

      StringBuffer line = new StringBuffer();

      boolean haveSeenNonContinueStatus= false;
      while (!haveSeenNonContinueStatus) {
        // parse status code line
        this.code = parseStatusLine(in, line); 
        // parse headers
        parseHeaders(in, line);
        haveSeenNonContinueStatus= code != 100; // 100 is "Continue"
      }

      readPlainContent(in);

      String contentEncoding = getHeader(Response.CONTENT_ENCODING);
      if ("gzip".equals(contentEncoding) || "x-gzip".equals(contentEncoding)) {
        content = http.processGzipEncoded(content, url);
      } else if ("deflate".equals(contentEncoding)) {
       content = http.processDeflateEncoded(content, url);
      } else {
        if (Http.LOG.isTraceEnabled()) {
          Http.LOG.trace("fetched " + content.length + " bytes from " + url);
        }
      }

    } finally {
      if (socket != null)
        socket.close();
    }

  }

  
  /* ------------------------- *
   * <implementation:Response> *
   * ------------------------- */
  
  public URL getUrl() {
    return url;
  }
  
  public int getCode() {
    return code;
  }

  public String getHeader(String name) {
    return headers.get(name);
  }
  
  public Metadata getHeaders() {
    return headers;
  }

  public byte[] getContent() {
    return content;
  }

  /* ------------------------- *
   * <implementation:Response> *
   * ------------------------- */
  

  private void readPlainContent(InputStream in) 
    throws HttpException, IOException {

    int contentLength = Integer.MAX_VALUE;    // get content length
    String contentLengthString = headers.get(Response.CONTENT_LENGTH);
    if (contentLengthString != null) {
      contentLengthString = contentLengthString.trim();
      try {
        if (!contentLengthString.isEmpty()) 
          contentLength = Integer.parseInt(contentLengthString);
      } catch (NumberFormatException e) {
        throw new HttpException("bad content length: "+contentLengthString);
      }
    }
    if (http.getMaxContent() >= 0
      && contentLength > http.getMaxContent()) {  // limit download size
      contentLength  = http.getMaxContent();
      headers.set(Nutch.FETCH_RESPONSE_TRUNCATED_KEY, "length");
    }

    ByteArrayOutputStream out = new ByteArrayOutputStream(Http.BUFFER_SIZE);
    byte[] bytes = new byte[Http.BUFFER_SIZE];
    int length = 0;                           // read content
    for (int i = in.read(bytes); i != -1 && length + i <= contentLength; i = in.read(bytes)) {
      out.write(bytes, 0, i);
      length += i;

      if (updateReadMovingAverage(i)) {
        if (getReadMovingAverage() < http.getMinThroughput()) {
          headers.set(Nutch.FETCH_RESPONSE_TRUNCATED_KEY, "time");
          Http.LOG.info("Bandwidth lower threshold reached: " + getReadMovingAverage() +
              " < " + http.getMinThroughput());
          content = out.toByteArray();
          return;
        }
      }

      long fetchDuration = new Date().getTime() / 1000 - fetchStarted;
      if (http.getMaxFetchDuration() > 0 && fetchDuration > http.getMaxFetchDuration()) {
        headers.set(Nutch.FETCH_RESPONSE_TRUNCATED_KEY, "time");
        Http.LOG.info("Fetch duration exceeded: " + fetchDuration + " > " + http.getMaxFetchDuration());
        content = out.toByteArray();
        return;
      }

    }
    if (length < contentLength) {
      headers.set(Nutch.FETCH_RESPONSE_TRUNCATED_KEY, "length");
    }
    content = out.toByteArray();
  }

  private void readChunkedContent(PushbackInputStream in,
                                  StringBuffer line) 
    throws HttpException, IOException {
    boolean doneChunks= false;
    int contentBytesRead= 0;
    byte[] bytes = new byte[Http.BUFFER_SIZE];
    ByteArrayOutputStream out = new ByteArrayOutputStream(Http.BUFFER_SIZE);

    while (!doneChunks) {
      if (Http.LOG.isTraceEnabled()) {
        Http.LOG.trace("Http: starting chunk");
      }

      readLine(in, line, false);

      String chunkLenStr;
      // if (LOG.isTraceEnabled()) { LOG.trace("chunk-header: '" + line + "'"); }

      int pos= line.indexOf(";");
      if (pos < 0) {
        chunkLenStr= line.toString();
      } else {
        chunkLenStr= line.substring(0, pos);
        // if (LOG.isTraceEnabled()) { LOG.trace("got chunk-ext: " + line.substring(pos+1)); }
      }
      chunkLenStr= chunkLenStr.trim();
      int chunkLen;
      try {
        chunkLen= Integer.parseInt(chunkLenStr, 16);
      } catch (NumberFormatException e){ 
        throw new HttpException("bad chunk length: "+line.toString());
      }

      if (chunkLen == 0) {
        doneChunks= true;
        break;
      }

      if ( (contentBytesRead + chunkLen) > http.getMaxContent() )
        chunkLen= http.getMaxContent() - contentBytesRead;

      // read one chunk
      int chunkBytesRead= 0;
      while (chunkBytesRead < chunkLen) {

        int toRead= (chunkLen - chunkBytesRead) < Http.BUFFER_SIZE ?
                    (chunkLen - chunkBytesRead) : Http.BUFFER_SIZE;
        int len= in.read(bytes, 0, toRead);

        if (len == -1) 
          throw new HttpException("chunk eof after " + contentBytesRead
                                      + " bytes in successful chunks"
                                      + " and " + chunkBytesRead 
                                      + " in current chunk");

        // DANGER!!! Will printed GZIPed stuff right to your
        // terminal!
        // if (LOG.isTraceEnabled()) { LOG.trace("read: " +  new String(bytes, 0, len)); }

        out.write(bytes, 0, len);
        chunkBytesRead+= len;  
      }

      readLine(in, line, false);

    }

    if (!doneChunks) {
      if (contentBytesRead != http.getMaxContent()) 
        throw new HttpException("chunk eof: !doneChunk && didn't max out");
      return;
    }

    content = out.toByteArray();
    parseHeaders(in, line);

  }

  private int parseStatusLine(PushbackInputStream in, StringBuffer line)
    throws IOException, HttpException {
    readLine(in, line, false);

    int codeStart = line.indexOf(" ");
    int codeEnd = line.indexOf(" ", codeStart+1);

    // handle lines with no plaintext result code, ie:
    // "HTTP/1.1 200" vs "HTTP/1.1 200 OK"
    if (codeEnd == -1) 
      codeEnd= line.length();

    int code;
    try {
      code= Integer.parseInt(line.substring(codeStart+1, codeEnd));
    } catch (NumberFormatException e) {
      throw new HttpException("bad status line '" + line 
                              + "': " + e.getMessage(), e);
    }

    headers.set(Nutch.FETCH_RESPONSE_VERBATIM_STATUS_KEY, line.toString());
    headers.set(Nutch.FETCH_RESPONSE_STATUS_CODE_KEY, Integer.toString(code));
    return code;
  }


  private void processHeaderLine(StringBuffer line)
    throws IOException, HttpException {

    int colonIndex = line.indexOf(":");       // key is up to colon
    if (colonIndex == -1) {
      int i;
      for (i= 0; i < line.length(); i++)
        if (!Character.isWhitespace(line.charAt(i)))
          break;
      if (i == line.length())
        return;
      throw new HttpException("No colon in header:" + line);
    }
    String key = line.substring(0, colonIndex);

    int valueStart = colonIndex+1;            // skip whitespace
    while (valueStart < line.length()) {
      int c = line.charAt(valueStart);
      if (c != ' ' && c != '\t')
        break;
      valueStart++;
    }
    String value = line.substring(valueStart);
    headers.set(key, value);
  }


  // Adds headers to our headers Metadata
  private void parseHeaders(PushbackInputStream in, StringBuffer line)
    throws IOException, HttpException {

    while (readLine(in, line, true) != 0) {

      // handle HTTP responses with missing blank line after headers
      int pos;
      if ( ((pos= line.indexOf("<!DOCTYPE")) != -1) 
           || ((pos= line.indexOf("<HTML")) != -1) 
           || ((pos= line.indexOf("<html")) != -1) ) {

        in.unread(line.substring(pos).getBytes("UTF-8"));
        line.setLength(pos);

        try {
            //TODO: (CM) We don't know the header names here
            //since we're just handling them generically. It would
            //be nice to provide some sort of mapping function here
            //for the returned header names to the standard metadata
            //names in the ParseData class
          processHeaderLine(line);
          verbatimResponseHeaders.append(line);
          verbatimResponseHeaders.append(CRLF);
        } catch (Exception e) {
          // fixme:
          Http.LOG.warn("Error: ", e);
        }
        return;
      }

      processHeaderLine(line);
      verbatimResponseHeaders.append(line);
      verbatimResponseHeaders.append(CRLF);
    }
    headers.set(Nutch.FETCH_RESPONSE_VERBATIM_HEADERS_KEY, verbatimResponseHeaders.toString());
  }

  private static int readLine(PushbackInputStream in, StringBuffer line,
                      boolean allowContinuedLine)
    throws IOException {
    line.setLength(0);
    for (int c = in.read(); c != -1; c = in.read()) {
      switch (c) {
        case '\r':
          if (peek(in) == '\n') {
            in.read();
          }
        case '\n': 
          if (line.length() > 0) {
            // at EOL -- check for continued line if the current
            // (possibly continued) line wasn't blank
            if (allowContinuedLine) 
              switch (peek(in)) {
                case ' ' : case '\t':                   // line is continued
                  in.read();
                  continue;
              }
          }
          return line.length();      // else complete
        default :
          line.append((char)c);
      }
    }
    throw new EOFException();
  }

  private static int peek(PushbackInputStream in) throws IOException {
    int value = in.read();
    in.unread(value);
    return value;
  }

  // Note: this will not deal with 60 second pauses well, but then the socket should time out so that doesn't happen
  public boolean updateReadMovingAverage(final int bytes) {
    final long now = new Date().getTime() / 1000;
    final int periods = throughputData.length;
    int pos = (int)(now % periods);

    if (throughputDataPos == -1) {
      for (int i = 0; i < periods; i++) {
        throughputData[i] = -1;
      }

      throughputData[pos] = bytes;
    } else if (throughputDataPos == pos) {
      throughputData[pos] += bytes;
    } else {
      throughputData[pos] = bytes;

      for (int i = throughputDataPos+1; i < Math.min(periods, pos); i++) {
        throughputData[i] = 0;
      }

      if (pos < throughputDataPos) {
        for (int i = 0; i < pos; i++) {
          throughputData[i] = 0;
        }
      }
    }

    boolean ret = throughputDataPos != pos;
    throughputDataPos = pos;
    return ret;
  }

  // We return Long.MAX_VALUE until we have at least half the samples (30 seconds) of data
  public long getReadMovingAverage() {
    final int periods = throughputData.length;
    long sum = 0;
    int numset = 0;
    for (int i = 0; i < periods; i++) {
      if (throughputData[i] != -1) {
        numset++;
        sum += throughputData[i];
      }
    }

    if (numset < periods / 2) {
      return Long.MAX_VALUE;
    }

    return sum / numset;
  }
}
