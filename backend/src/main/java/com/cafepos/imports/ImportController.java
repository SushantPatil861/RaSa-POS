package com.cafepos.imports;

import com.cafepos.platform.ApiException;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;

@RestController
@RequestMapping("/api/v1")
public class ImportController {

    public record CommitRequest(Boolean includeDuplicates) {}
    public record ReasonRequest(String reason) {}

    private final ImportService imports;

    public ImportController(ImportService imports) {
        this.imports = imports;
    }

    @GetMapping("/imports")
    @PreAuthorize("hasAuthority('imports.manage')")
    public List<ImportService.BatchDto> list() {
        return imports.list();
    }

    @PostMapping("/imports")
    @PreAuthorize("hasAuthority('imports.manage')")
    public ImportService.ImportDetail upload(@RequestParam("file") MultipartFile file,
                                             @RequestParam(defaultValue = "EXPENSES") String targetType,
                                             @RequestParam(defaultValue = "true") boolean hasHeader) {
        try {
            return imports.upload(file.getOriginalFilename(), file.getBytes(), targetType, hasHeader);
        } catch (IOException e) {
            throw ApiException.badRequest("The file could not be read");
        }
    }

    @GetMapping("/imports/{id}")
    @PreAuthorize("hasAuthority('imports.manage')")
    public ImportService.ImportDetail get(@PathVariable long id, @RequestParam(required = false) String targetType) {
        return imports.detail(id, targetType);
    }

    @PutMapping("/imports/{id}/mapping")
    @PreAuthorize("hasAuthority('imports.manage')")
    public ImportService.ImportDetail mapping(@PathVariable long id, @RequestBody ImportService.MappingRequest r) {
        return imports.saveMapping(id, r);
    }

    @PostMapping("/imports/{id}/preview")
    @PreAuthorize("hasAuthority('imports.manage')")
    public ImportService.PreviewResult preview(@PathVariable long id) {
        return imports.preview(id);
    }

    @GetMapping("/imports/{id}/rows")
    @PreAuthorize("hasAuthority('imports.manage')")
    public List<ImportService.RowDto> rows(@PathVariable long id, @RequestParam(required = false) String status) {
        return imports.rows(id, status);
    }

    @PostMapping("/imports/{id}/commit")
    @PreAuthorize("hasAuthority('imports.manage')")
    public ImportService.CommitResult commit(@PathVariable long id, @RequestBody(required = false) CommitRequest r) {
        return imports.commit(id, r != null && Boolean.TRUE.equals(r.includeDuplicates()));
    }

    @PostMapping("/imports/{id}/undo")
    @PreAuthorize("hasAuthority('imports.manage')")
    public ImportService.BatchDto undo(@PathVariable long id, @RequestBody ReasonRequest r) {
        return imports.undo(id, r.reason());
    }

    @PostMapping("/imports/{id}/reject")
    @PreAuthorize("hasAuthority('imports.manage')")
    public ImportService.BatchDto reject(@PathVariable long id, @RequestBody(required = false) ReasonRequest r) {
        return imports.reject(id, r == null ? null : r.reason());
    }
}
