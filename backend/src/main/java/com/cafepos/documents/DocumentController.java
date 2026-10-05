package com.cafepos.documents;

import com.cafepos.platform.ApiException;
import org.springframework.http.ContentDisposition;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class DocumentController {

    public record ReasonRequest(String reason) {}

    private final DocumentService documents;

    public DocumentController(DocumentService documents) {
        this.documents = documents;
    }

    @PostMapping("/documents")
    @PreAuthorize("hasAuthority('documents.upload')")
    public DocumentService.DocumentDetail upload(@RequestParam("file") MultipartFile file,
                                                 @RequestParam(defaultValue = "OTHER") String docType) {
        try {
            return documents.upload(file.getOriginalFilename(), file.getBytes(), docType);
        } catch (IOException e) {
            throw ApiException.badRequest("The file could not be read");
        }
    }

    @GetMapping("/documents")
    @PreAuthorize("hasAuthority('documents.view')")
    public List<DocumentService.DocumentDto> list(@RequestParam(required = false) String status,
                                                  @RequestParam(required = false) String docType,
                                                  @RequestParam(defaultValue = "200") int limit) {
        return documents.list(status, docType, limit);
    }

    @GetMapping("/documents/{id}")
    @PreAuthorize("hasAuthority('documents.view')")
    public DocumentService.DocumentDetail get(@PathVariable long id) {
        return documents.get(id);
    }

    @GetMapping("/documents/{id}/file")
    @PreAuthorize("hasAuthority('documents.view')")
    public ResponseEntity<byte[]> file(@PathVariable long id) {
        DocumentService.FileData f = documents.file(id);
        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(f.contentType()))
                .header("Content-Disposition", ContentDisposition.inline().filename(f.name(), StandardCharsets.UTF_8).build().toString())
                .header("X-Content-Type-Options", "nosniff")
                .header("Cache-Control", "private, max-age=3600")
                .body(f.bytes());
    }

    @PostMapping("/documents/{id}/reprocess")
    @PreAuthorize("hasAuthority('documents.confirm')")
    public DocumentService.DocumentDetail reprocess(@PathVariable long id) {
        return documents.reprocess(id);
    }

    @PostMapping("/documents/{id}/confirm")
    @PreAuthorize("hasAuthority('documents.confirm')")
    public DocumentService.DocumentDetail confirm(@PathVariable long id, @RequestBody DocumentService.ConfirmRequest r) {
        return documents.confirm(id, r);
    }

    @PostMapping("/documents/{id}/reject")
    @PreAuthorize("hasAuthority('documents.confirm')")
    public DocumentService.DocumentDetail reject(@PathVariable long id, @RequestBody ReasonRequest r) {
        return documents.reject(id, r.reason());
    }
}
