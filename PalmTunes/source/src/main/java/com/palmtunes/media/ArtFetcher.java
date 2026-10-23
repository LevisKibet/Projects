package com.palmtunes.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Locale;

/** Loads cover art bytes from the URL a player published. */
public final class ArtFetcher {
    private static final int MAX_BYTES = 8 * 1024 * 1024;

    private static final HttpClient HTTP = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(4))
            .followRedirects(HttpClient.Redirect.NORMAL)
            .build();

    private ArtFetcher() {
    }

    /**
     * @param allowRemote whether http(s) URLs may be fetched (config switch); file URLs are always allowed
     */
    public static byte[] fetch(String url, boolean allowRemote) throws IOException, InterruptedException {
        URI uri;
        try {
            uri = URI.create(url.trim());
        } catch (IllegalArgumentException e) {
            throw new IOException("Bad art URL", e);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        switch (scheme) {
            case "file":
                return readFile(uri);
            case "http":
            case "https":
                if (!allowRemote) {
                    throw new IOException("Remote art disabled");
                }
                return readRemote(uri);
            default:
                throw new IOException("Unsupported art URL scheme: " + scheme);
        }
    }

    private static byte[] readFile(URI uri) throws IOException {
        Path path = Path.of(uri);
        long size = Files.size(path);
        if (size > MAX_BYTES) {
            throw new IOException("Art file too large");
        }
        return Files.readAllBytes(path);
    }

    private static byte[] readRemote(URI uri) throws IOException, InterruptedException {
        rejectLocalHosts(uri);
        HttpRequest request = HttpRequest.newBuilder(uri)
                .timeout(Duration.ofSeconds(6))
                .header("User-Agent", "PalmTunes/0.1")
                .GET()
                .build();
        HttpResponse<InputStream> response = HTTP.send(request, HttpResponse.BodyHandlers.ofInputStream());
        try (InputStream in = response.body()) {
            if (response.statusCode() / 100 != 2) {
                throw new IOException("HTTP " + response.statusCode());
            }
            byte[] data = in.readNBytes(MAX_BYTES + 1);
            if (data.length > MAX_BYTES) {
                throw new IOException("Art too large");
            }
            return data;
        }
    }

    /** A web page driving a browser's media session must not be able to make us poke the local network. */
    private static void rejectLocalHosts(URI uri) throws IOException {
        String host = uri.getHost();
        if (host == null) {
            throw new IOException("No host in art URL");
        }
        for (InetAddress address : InetAddress.getAllByName(host)) {
            if (address.isAnyLocalAddress() || address.isLoopbackAddress()
                    || address.isLinkLocalAddress() || address.isSiteLocalAddress()) {
                throw new IOException("Refusing to fetch art from a local address");
            }
        }
    }
}
