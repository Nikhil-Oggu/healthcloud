package com.healthcloud.document;

/**
 * The authorized bytes of a document plus the little metadata the download response needs (filename +
 * content type). Returned by the service only after access has been checked; the controller streams it back.
 */
public record DocumentContent(String fileName, String contentType, byte[] bytes) {
}
