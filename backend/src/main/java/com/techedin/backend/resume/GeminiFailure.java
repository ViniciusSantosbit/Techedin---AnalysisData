package com.techedin.backend.resume;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/** Translate provider failures without exposing provider bodies, prompts or credentials. */
final class GeminiFailure {
    private GeminiFailure() {}
    static ResponseStatusException fromStatus(int providerStatus) {
        org.slf4j.LoggerFactory.getLogger(GeminiFailure.class).warn("Gemini retornou HTTP {}. Revise a configuração e a cota do provedor.", providerStatus);
        return switch (providerStatus) {
            case 401, 403 -> error(503, "A chave do Gemini não foi autorizada. Revise GEMINI_API_KEY e as permissões do projeto no servidor.");
            case 404 -> error(503, "O modelo do Gemini não está disponível para esta chave. Revise GEMINI_MODEL no servidor.");
            case 429 -> error(429, "O limite de uso do Gemini foi atingido. Confira a cota do projeto no Google AI Studio ou tente mais tarde.");
            case 400 -> error(502, "O Gemini rejeitou a solicitação. Confira o modelo e sua configuração no servidor.");
            case 500, 502, 503, 504 -> error(503, "O serviço do Gemini está temporariamente indisponível. Tente novamente mais tarde.");
            default -> error(502, "Não foi possível obter uma resposta do Gemini. Confira os logs da API.");
        };
    }
    private static ResponseStatusException error(int status, String message) {
        return new ResponseStatusException(HttpStatus.valueOf(status), message);
    }
}
