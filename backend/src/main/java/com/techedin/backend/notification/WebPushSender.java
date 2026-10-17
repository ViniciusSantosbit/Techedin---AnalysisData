package com.techedin.backend.notification;

import com.nimbusds.jose.*;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.util.Base64URL;
import com.nimbusds.jwt.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.*;
import java.util.*;

/** VAPID authenticated Web Push with an empty body: no CV or job details leave the backend. */
@Component
public class WebPushSender {
    private final String publicKey, subject;
    private ECKey key;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(8)).followRedirects(HttpClient.Redirect.NEVER).build();
    public WebPushSender(@Value("${WEB_PUSH_ENABLED:false}") boolean enabled,
            @Value("${WEB_PUSH_PUBLIC_KEY:}") String publicKey, @Value("${WEB_PUSH_PRIVATE_KEY:}") String privateKey,
            @Value("${WEB_PUSH_SUBJECT:https://techedin.vercel.app}") String subject) {
        this.publicKey = publicKey; this.subject = subject;
        if (!enabled) return;
        try {
            byte[] point = Base64.getUrlDecoder().decode(publicKey);
            if (point.length != 65 || point[0] != 4 || Base64.getUrlDecoder().decode(privateKey).length != 32) return;
            URI contact = URI.create(subject);
            if (!(contact.getScheme().equals("https") && contact.getHost() != null || contact.getScheme().equals("mailto"))) return;
            key = new ECKey.Builder(Curve.P_256, Base64URL.encode(Arrays.copyOfRange(point,1,33)), Base64URL.encode(Arrays.copyOfRange(point,33,65)))
                .d(new Base64URL(privateKey)).build();
            if (!SignedJWT.parse(token(URI.create("https://fcm.googleapis.com"))).verify(new ECDSAVerifier(key.toECPublicKey()))) key=null;
        } catch (Exception exception) { key=null; }
    }
    public boolean configured() { return key != null; }
    public String publicKey() { return publicKey; }
    static URI endpoint(String value) {
        try {
            URI uri = URI.create(value); String host=uri.getHost();
            if (!"https".equals(uri.getScheme()) || host==null || uri.getUserInfo()!=null || uri.getFragment()!=null
                || uri.getPort()!=-1 || uri.getRawPath()==null || uri.getRawPath().length()<2 || value.length()>4096) throw new IllegalArgumentException();
            if (!(host.equals("fcm.googleapis.com") || host.equals("updates.push.services.mozilla.com") || host.equals("web.push.apple.com"))) throw new IllegalArgumentException();
            return uri;
        } catch (Exception exception) { throw new IllegalArgumentException("Serviço de notificações não reconhecido."); }
    }
    String token(URI endpoint) throws JOSEException {
        var claims = new JWTClaimsSet.Builder().audience(endpoint.getScheme()+"://"+endpoint.getHost())
            .expirationTime(Date.from(Instant.now().plusSeconds(3600))).subject(subject).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.ES256).type(JOSEObjectType.JWT).build(),claims);
        jwt.sign(new ECDSASigner(key)); return jwt.serialize();
    }
    public int send(String value) throws Exception {
        if (!configured()) throw new IllegalStateException("Push não configurado");
        URI uri=endpoint(value);
        var request=HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(15))
            .header("TTL","3600").header("Urgency","normal").header("Topic","techedin-jobs")
            .header("Authorization","vapid t="+token(uri)+", k="+publicKey)
            .POST(HttpRequest.BodyPublishers.noBody()).build();
        return http.send(request,HttpResponse.BodyHandlers.discarding()).statusCode();
    }
}
