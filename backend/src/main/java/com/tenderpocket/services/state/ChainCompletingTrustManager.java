package com.tenderpocket.services.state;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import javax.net.ssl.X509TrustManager;
import java.io.InputStream;
import java.net.Socket;
import java.security.KeyStore;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Validates server certificates with the JVM's normal trust store, but repairs one common server
 * misconfiguration: a site that sends only its own certificate and leaves out the issuing CA's
 * intermediate (Gujarat nProcure and Bihar eProc2 do this). Browsers fetch the missing intermediate;
 * here the needed public intermediates are bundled under {@code resources/certs/}.
 *
 * <p>When the default check fails, the missing intermediate is appended to the presented chain and
 * the default trust manager checks it again, so the trust anchor is still one of the JVM's trusted
 * roots and hostname verification still applies. Nothing that the default would reject for any
 * other reason is accepted.
 */
public final class ChainCompletingTrustManager extends X509ExtendedTrustManager {

    private static final String[] BUNDLED = {"certs/sectigo-public-server-authentication-ca-ov-r36.pem"};

    private final X509ExtendedTrustManager delegate;
    private final List<X509Certificate> intermediates;

    ChainCompletingTrustManager(X509ExtendedTrustManager delegate, List<X509Certificate> intermediates) {
        this.delegate = delegate;
        this.intermediates = intermediates;
    }

    /** SSLContext using the JVM trust store plus chain completion. Falls back to the default context on error. */
    public static SSLContext sslContext() {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init((KeyStore) null);
            X509ExtendedTrustManager def = null;
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager x) def = x;
            }
            if (def == null) return SSLContext.getDefault();
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new TrustManager[]{new ChainCompletingTrustManager(def, loadBundled())}, null);
            return ctx;
        } catch (Exception e) {
            try {
                return SSLContext.getDefault();
            } catch (Exception inner) {
                throw new IllegalStateException(inner);
            }
        }
    }

    static List<X509Certificate> loadBundled() {
        List<X509Certificate> out = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            for (String path : BUNDLED) {
                try (InputStream in = ChainCompletingTrustManager.class.getClassLoader().getResourceAsStream(path)) {
                    if (in == null) continue;
                    out.add((X509Certificate) cf.generateCertificate(in));
                }
            }
        } catch (Exception ignored) {
            // Without bundled certificates this behaves exactly like the default trust manager.
        }
        return out;
    }

    /** The presented chain plus any bundled intermediate that issued its last certificate, or null. */
    X509Certificate[] complete(X509Certificate[] chain) {
        if (chain == null || chain.length == 0) return null;
        X509Certificate last = chain[chain.length - 1];
        for (X509Certificate ca : intermediates) {
            if (!ca.getSubjectX500Principal().equals(last.getIssuerX500Principal())) continue;
            try {
                last.verify(ca.getPublicKey());
            } catch (Exception e) {
                continue;
            }
            X509Certificate[] longer = Arrays.copyOf(chain, chain.length + 1);
            longer[chain.length] = ca;
            return longer;
        }
        return null;
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        try {
            delegate.checkServerTrusted(chain, authType, engine);
        } catch (CertificateException e) {
            X509Certificate[] completed = complete(chain);
            if (completed == null) throw e;
            delegate.checkServerTrusted(completed, authType, engine);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        try {
            delegate.checkServerTrusted(chain, authType, socket);
        } catch (CertificateException e) {
            X509Certificate[] completed = complete(chain);
            if (completed == null) throw e;
            delegate.checkServerTrusted(completed, authType, socket);
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        try {
            delegate.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            X509Certificate[] completed = complete(chain);
            if (completed == null) throw e;
            delegate.checkServerTrusted(completed, authType);
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, engine);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        delegate.checkClientTrusted(chain, authType, socket);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        delegate.checkClientTrusted(chain, authType);
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return delegate.getAcceptedIssuers();
    }

    /** Exposed for tests. */
    static X509TrustManager forTest(X509ExtendedTrustManager delegate, List<X509Certificate> intermediates) {
        return new ChainCompletingTrustManager(delegate, intermediates);
    }
}
