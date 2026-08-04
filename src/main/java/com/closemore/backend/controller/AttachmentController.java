package com.closemore.backend.controller;

import com.closemore.backend.dto.ActivityAttachmentResponse;
import com.closemore.backend.service.AttachmentService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Attachments on activities.
 *
 * <p><b>The path shapes here are inconsistent, and that is faithful rather than sloppy.</b> The list,
 * upload and download routes are keyed by the ACTIVITY id; delete is keyed by the ATTACHMENT id.
 * That is how the Next.js backend exposes them, and Section 3 of the migration plan commits to the
 * same URLs. Regularising it would be a redesign shipped inside a port - noted in the plan's open
 * items instead.
 *
 * <p>The service layer takes and returns plain records rather than {@code MultipartFile} and
 * {@code ResponseEntity}, so that unwrapping the web types is this class's job and the service can
 * be called from a test or a future queue consumer with no servlet request in scope.
 */
@RestController
@RequestMapping("/api/v1/attachments")
@RequiredArgsConstructor
public class AttachmentController {

    private final AttachmentService attachmentService;

    /** GET /api/v1/attachments/{logId} - everything attached to one activity. */
    @GetMapping("/{logId}")
    public List<ActivityAttachmentResponse> list(@PathVariable String logId) {
        return attachmentService.listForActivity(logId);
    }

    /**
     * POST /api/v1/attachments/{logId} - upload one or more files.
     *
     * <p>The parameter is named {@code files} and is a list, because the plan specifies "one or more"
     * and a single-file signature would need a second endpoint later. Sending one file to a list
     * parameter works unchanged, so the plural costs nothing.
     *
     * <p>Reading the bytes here rather than passing the stream down is deliberate: the stream is tied
     * to the request, and the service holds it across a transaction boundary and a storage write. The
     * ceiling on how much memory that can consume is
     * {@code spring.servlet.multipart.max-request-size}, which is why that property is set explicitly
     * rather than left at its default.
     */
    @PostMapping("/{logId}")
    public ResponseEntity<List<ActivityAttachmentResponse>> upload(
            @PathVariable String logId,
            @RequestParam("files") List<MultipartFile> files) throws IOException {

        List<AttachmentService.UploadedFile> uploads = new ArrayList<>();
        for (MultipartFile file : files) {
            uploads.add(new AttachmentService.UploadedFile(
                    // getOriginalFilename is null for a part with no filename, and it is client-
                    // controlled in every case. The storage provider treats it as untrusted; here it
                    // only needs to be non-null for the NOT NULL column.
                    file.getOriginalFilename() == null ? "file" : file.getOriginalFilename(),
                    file.getContentType() == null
                            ? MediaType.APPLICATION_OCTET_STREAM_VALUE
                            : file.getContentType(),
                    file.getBytes()));
        }

        return ResponseEntity.status(HttpStatus.CREATED)
                .body(attachmentService.upload(logId, uploads));
    }

    /**
     * GET /api/v1/attachments/{logId}/open/{attachmentId} - stream the file back.
     *
     * <p>Content-Disposition is {@code attachment}, not {@code inline}. Serving user-uploaded content
     * inline lets an uploaded HTML or SVG file execute in the origin of the application, which turns
     * an upload feature into stored cross-site scripting against everyone who opens it. The filename
     * is RFC 5987 encoded because it is user input and may contain quotes, semicolons or non-ASCII -
     * any of which would otherwise break out of the header or corrupt it.
     */
    @GetMapping("/{logId}/open/{attachmentId}")
    public ResponseEntity<Resource> open(@PathVariable String logId,
                                         @PathVariable String attachmentId) {
        AttachmentService.DownloadedFile file = attachmentService.download(logId, attachmentId);

        String encodedName = URLEncoder.encode(file.fileName(), StandardCharsets.UTF_8)
                .replace("+", "%20");

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        "attachment; filename*=UTF-8''" + encodedName)
                .header(HttpHeaders.CONTENT_TYPE, file.mimeType())
                .contentLength(file.content().length)
                .body(new ByteArrayResource(file.content()));
    }

    /** DELETE /api/v1/attachments/{attachmentId} - uploader or Admin only. */
    @DeleteMapping("/{attachmentId}")
    public ResponseEntity<Void> delete(@PathVariable String attachmentId) {
        attachmentService.delete(attachmentId);
        return ResponseEntity.noContent().build();
    }
}
