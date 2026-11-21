package com.tenderpocket.services.state;

import org.jsoup.nodes.Document;

import java.io.IOException;

/** Fetches portal pages within one session. Abstracted so the sync logic can be tested offline. */
public interface PageFetcher {

    Document get(String url) throws IOException, InterruptedException;

    int requestCount();
}
