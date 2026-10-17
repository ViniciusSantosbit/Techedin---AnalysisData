package com.techedin.backend.notification;
import com.nimbusds.jose.crypto.*;
import com.nimbusds.jose.jwk.*;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.SignedJWT;
import org.junit.jupiter.api.Test;
import java.net.URI;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class WebPushSenderTest {
    @Test void signsValidOriginBoundVapidTokenWithoutExposingPrivateKey() throws Exception {
        var key=new ECKeyGenerator(Curve.P_256).generate();
        byte[] point=new byte[65];point[0]=4;
        System.arraycopy(key.getX().decode(),0,point,1,32);System.arraycopy(key.getY().decode(),0,point,33,32);
        String publicKey=Base64.getUrlEncoder().withoutPadding().encodeToString(point);
        var sender=new WebPushSender(true,publicKey,key.getD().toString(),"https://techedin.vercel.app");
        assertTrue(sender.configured());
        var jwt=SignedJWT.parse(sender.token(URI.create("https://fcm.googleapis.com/fcm/send/fixture")));
        assertTrue(jwt.verify(new ECDSAVerifier(key.toECPublicKey())));
        assertEquals(List.of("https://fcm.googleapis.com"),jwt.getJWTClaimsSet().getAudience());
        assertTrue(jwt.getJWTClaimsSet().getExpirationTime().after(new Date()));
        assertEquals(publicKey,sender.publicKey());
        assertFalse(new WebPushSender(true,publicKey,"invalid","https://techedin.vercel.app").configured());
    }
    @Test void onlyAcceptsRecognizedHttpsPushProviders() {
        assertDoesNotThrow(()->WebPushSender.endpoint("https://web.push.apple.com/fixture"));
        for (String value:List.of("http://fcm.googleapis.com/fcm/send/a","https://127.0.0.1/a","https://fcm.googleapis.com.evil.example/a","https://fcm.googleapis.com:443/a","https://user@fcm.googleapis.com/a"))
            assertThrows(IllegalArgumentException.class,()->WebPushSender.endpoint(value));
    }
}
