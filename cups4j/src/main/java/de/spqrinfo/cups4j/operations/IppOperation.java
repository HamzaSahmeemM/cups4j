package de.spqrinfo.cups4j.operations;

/**
 * Copyright (C) 2009 Harald Weyhing
 * <p>
 * This program is free software; you can redistribute it and/or modify it under the terms of the
 * GNU Lesser General Public License as published by the Free Software Foundation; either version 3
 * of the License, or (at your option) any later version.
 * <p>
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY; without
 * even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.
 * <p>
 * See the GNU Lesser General Public License for more details. You should have received a copy of
 * the GNU Lesser General Public License along with this program; if not, see
 * <http://www.gnu.org/licenses/>.
 */

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.SequenceInputStream;
import java.io.UnsupportedEncodingException;
import java.net.URI;
import java.net.URL;
import java.nio.ByteBuffer;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.config.RequestConfig;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.client5.http.impl.classic.HttpClients;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.ContentType;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.http.io.entity.InputStreamEntity;
import org.apache.hc.core5.util.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import de.spqrinfo.cups4j.CupsClient;
import de.spqrinfo.vppserver.ippclient.IppResponse;
import de.spqrinfo.vppserver.ippclient.IppResult;
import de.spqrinfo.vppserver.ippclient.IppTag;
import de.spqrinfo.vppserver.schema.ippclient.Attribute;
import jakarta.xml.bind.JAXBException;

public abstract class IppOperation {
    protected short operationID = -1; // IPP operation ID
    protected short bufferSize = 8192; // BufferSize for this operation
    protected int ippPort = CupsClient.DEFAULT_PORT;

    private final static String IPP_MIME_TYPE = "application/ipp";

    private static final Logger logger = LoggerFactory.getLogger(IppOperation.class);

    //
    String httpStatusLine = null;

    /**
     * Gets the IPP header
     *
     * @param url
     * @return IPP header
     * @throws UnsupportedEncodingException
     */
    public ByteBuffer getIppHeader(URL url) throws UnsupportedEncodingException {
        return getIppHeader(url, null);
    }

    public IppResult request(URL url, Map<String, String> map) throws Exception {
        return sendRequest(url, getIppHeader(url, map));
    }

    public IppResult request(URL url, Map<String, String> map, InputStream document) throws Exception {
        return sendRequest(url, getIppHeader(url, map), document);
    }

    /**
     * Gets the IPP header
     *
     * @param url
     * @param map
     * @return IPP header
     * @throws UnsupportedEncodingException
     */
    public ByteBuffer getIppHeader(URL url, Map<String, String> map) throws UnsupportedEncodingException {
        if (url == null) {
            logger.error("IppOperation.getIppHeader(): uri is null");
            return null;
        }

        ByteBuffer ippBuf = ByteBuffer.allocateDirect(bufferSize);
        ippBuf = IppTag.getOperation(ippBuf, operationID);
        ippBuf = IppTag.getUri(ippBuf, "printer-uri", stripPortNumber(url));

        if (map == null) {
            ippBuf = IppTag.getEnd(ippBuf);
            ippBuf.flip();
            return ippBuf;
        }

        ippBuf = IppTag.getNameWithoutLanguage(ippBuf, "requesting-user-name", map.get("requesting-user-name"));

        if (map.get("limit") != null) {
            int value = Integer.parseInt(map.get("limit"));
            ippBuf = IppTag.getInteger(ippBuf, "limit", value);
        }

        if (map.get("requested-attributes") != null) {
            String[] sta = map.get("requested-attributes").split(" ");
            if (sta != null) {
                ippBuf = IppTag.getKeyword(ippBuf, "requested-attributes", sta[0]);
                int l = sta.length;
                for (int i = 1; i < l; i++) {
                    ippBuf = IppTag.getKeyword(ippBuf, null, sta[i]);
                }
            }
        }

        ippBuf = IppTag.getEnd(ippBuf);
        ippBuf.flip();
        return ippBuf;
    }

    /**
     * Sends a request to the provided URL
     *
     * @param url
     * @param ippBuf
     *
     * @return result
     * @throws IOException
     * @throws JAXBException
     */
    private IppResult sendRequest(URL url, ByteBuffer ippBuf) throws Exception {
        return sendRequest(url, ippBuf, null);
    }

    /**
     * Sends a request to the provided url
     *
     * @param url
     * @param ippBuf
     *
     * @param documentStream
     * @return result
     * @throws Exception
     */
 private IppResult sendRequest(URL url, ByteBuffer ippBuf, InputStream documentStream) throws Exception {
    // Validate input
    if (ippBuf == null || url == null) {
        return null;
    }

    // Configure timeouts, protocol version, and connection settings
    RequestConfig requestConfig = RequestConfig.custom()
        .setConnectionRequestTimeout(Timeout.ofSeconds(10))   // Connection timeout
        .setResponseTimeout(Timeout.ofSeconds(10))            // Response timeout
        .setExpectContinueEnabled(true)                        // Expect-continue enabled
        .setResponseTimeout(Timeout.ofSeconds(10))               // Socket timeout
        .build();

    // Create HttpClient with custom configuration
    try (CloseableHttpClient client = HttpClients.custom()
        .setDefaultRequestConfig(requestConfig) // Apply requestConfig
        .build()) {

        // Construct the full URI
        URI fullUri = new URI("http://" + url.getHost() + ":" + ippPort + url.getPath());

        // Create HttpPost request
        HttpPost httpPost = new HttpPost(fullUri);

        // Prepare IPP request body
        byte[] bytes = new byte[ippBuf.limit()];
        ippBuf.get(bytes);
        ByteArrayInputStream headerStream = new ByteArrayInputStream(bytes);

        // Combine streams if document stream is present
        InputStream inputStream = documentStream != null 
            ? new SequenceInputStream(headerStream, documentStream) 
            : headerStream;

        // Create input stream entity with content type
        InputStreamEntity requestEntity = new InputStreamEntity(
            inputStream, 
            -1,  // Use -1 to read until EOF
            ContentType.parse(IPP_MIME_TYPE)
        );

        // Set the entity to the request
        httpPost.setEntity(requestEntity);

        // Define response handler
        HttpClientResponseHandler<byte[]> responseHandler = new HttpClientResponseHandler<>() {
            @Override
            public byte[] handleResponse(ClassicHttpResponse response) throws IOException {
                // Store HTTP status line
                httpStatusLine = response.getReasonPhrase();

                // Process response entity
                HttpEntity entity = response.getEntity();
                return entity != null 
                    ? EntityUtils.toByteArray(entity) 
                    : null;
            }
        };

        // Execute request and get response
        byte[] result = client.execute(httpPost, responseHandler);

        // Process IPP response
        IppResponse ippResponse = new IppResponse();
        IppResult ippResult = ippResponse.getResponse(ByteBuffer.wrap(result));
        ippResult.setHttpStatusResponse(httpStatusLine);

        return ippResult;
    }
}


    /**
     * Removes the port number in the submitted URL
     *
     * @param url
     * @return url without port number
     */
    protected String stripPortNumber(URL url) {
        String protocol = url.getProtocol();
        if ("ipp".equals(protocol)) {
            protocol = "http";
        }

        return protocol + "://" + url.getHost() + url.getPath();
    }

    protected String getAttributeValue(Attribute attr) {
        return attr.getAttributeValue().get(0).getValue();
    }
}
