package com.techedin.backend.resume;

import com.techedin.backend.account.AccountPrincipal;
import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.util.UUID;

@RestController
@RequestMapping("/api/account/resume")
public class ResumeController {
    public record ResumeState(ResumeService.ResumeView resume) {}
    public record Revision(@NotNull UUID revision) {}
    public record TextRevision(@NotNull UUID revision, @NotBlank @Size(max=100000) String text) {}
    private final ResumeService service;
    private final ResumeExtractor extractor;
    public ResumeController(ResumeService service, ResumeExtractor extractor) { this.service = service; this.extractor = extractor; }
    @GetMapping public ResumeState get(@AuthenticationPrincipal AccountPrincipal user) { return new ResumeState(service.get(user.id())); }
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResumeState upload(@AuthenticationPrincipal AccountPrincipal user, @RequestParam("file") MultipartFile file,
            @RequestParam(value="revision", required=false) UUID revision) {
        return new ResumeState(service.store(user.id(), revision, extractor.extract(file)));
    }
    @PutMapping public ResumeState revise(@AuthenticationPrincipal AccountPrincipal user, @Valid @RequestBody TextRevision body) {
        return new ResumeState(service.revise(user.id(), body.revision(), body.text()));
    }
    @DeleteMapping @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@AuthenticationPrincipal AccountPrincipal user, @Valid @RequestBody Revision body) {
        service.delete(user.id(), body.revision());
    }
}
