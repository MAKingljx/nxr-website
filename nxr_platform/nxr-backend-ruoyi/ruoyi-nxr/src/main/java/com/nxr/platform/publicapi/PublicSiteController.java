package com.nxr.platform.publicapi;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.ruoyi.common.annotation.Anonymous;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.StreamingResponseBody;
import org.springframework.web.server.ResponseStatusException;

@Anonymous
@RestController
@RequestMapping("/api/public")
public class PublicSiteController {

    private final PublicSiteService publicSiteService;
    private final PublicAiCharacterService publicAiCharacterService;
    private final ObjectMapper objectMapper;

    public PublicSiteController(
        PublicSiteService publicSiteService,
        PublicAiCharacterService publicAiCharacterService,
        ObjectMapper objectMapper
    ) {
        this.publicSiteService = publicSiteService;
        this.publicAiCharacterService = publicAiCharacterService;
        this.objectMapper = objectMapper;
    }

    @GetMapping("/overview")
    public PublicSiteService.PublicOverviewResponse overview() {
        return publicSiteService.loadOverview();
    }

    @GetMapping("/waitlist-count")
    public PublicSiteService.WaitlistCountResponse waitlistCount() {
        return publicSiteService.loadWaitlistCount();
    }

    @PostMapping("/waitlist")
    public PublicSiteService.WaitlistSignupResponse joinWaitlist(
        @RequestBody PublicSiteService.WaitlistSignupRequest request
    ) {
        return publicSiteService.joinWaitlist(request);
    }

    @GetMapping("/cards/{certId}")
    public PublicSiteService.PublicCardResponse publishedCard(@PathVariable String certId) {
        return publicSiteService.loadPublishedCard(certId)
            .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Certificate not found"));
    }

    @PostMapping("/ai-character-info")
    public PublicAiCharacterService.AiCharacterResponse aiCharacterInfo(
        @RequestBody PublicAiCharacterService.AiCharacterRequest request
    ) {
        return publicAiCharacterService.loadCharacterInfo(request);
    }

    @PostMapping(value = "/ai-character-info/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<StreamingResponseBody> aiCharacterInfoStream(
        @RequestBody PublicAiCharacterService.AiCharacterRequest request
    ) {
        StreamingResponseBody body = outputStream -> {
            try {
                PublicAiCharacterService.AiCharacterResponse response = publicAiCharacterService.streamCharacterInfo(
                    request,
                    chunk -> {
                        try {
                            outputStream.write(
                                sse("chunk", java.util.Map.of("content", chunk)).getBytes(StandardCharsets.UTF_8)
                            );
                            outputStream.flush();
                        } catch (java.io.IOException exc) {
                            throw new UncheckedIOException(exc);
                        }
                    }
                );
                outputStream.write(sse("done", response).getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            } catch (UncheckedIOException exc) {
                throw exc.getCause();
            } catch (RuntimeException exc) {
                outputStream.write(sse("error", java.util.Map.of(
                    "message", "Unable to generate character information right now."
                )).getBytes(StandardCharsets.UTF_8));
                outputStream.flush();
            }
        };

        return ResponseEntity.ok()
            .contentType(MediaType.TEXT_EVENT_STREAM)
            .body(body);
    }

    private String sse(String eventName, Object payload) {
        try {
            return "event: " + eventName + "\n" + "data: " + objectMapper.writeValueAsString(payload) + "\n\n";
        } catch (JsonProcessingException exception) {
            throw new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Failed to encode event stream", exception);
        }
    }
}
