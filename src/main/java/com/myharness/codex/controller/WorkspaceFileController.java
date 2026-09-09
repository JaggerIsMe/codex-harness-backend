package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.WorkspaceFileRequestDTO;
import com.myharness.codex.entity.vo.*;
import com.myharness.codex.security.UserContext;
import com.myharness.codex.service.WorkspaceFileService;
import org.springframework.core.io.Resource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import java.io.IOException;

@RestController
@RequestMapping("/api/v1/projects/{pid}/workspace-files")
public class WorkspaceFileController {
    private final WorkspaceFileService files;
    private final com.myharness.codex.service.WorkspaceFilePreviewService previews;
    public WorkspaceFileController(WorkspaceFileService files,com.myharness.codex.service.WorkspaceFilePreviewService previews) {this.files=files;this.previews=previews;}
    @GetMapping
    public ApiResponseVO<WorkspaceDirectoryVO> directory(@PathVariable Long pid,@RequestParam(defaultValue="") String path,
            @RequestParam(defaultValue="") String cursor,@RequestParam(defaultValue="false") boolean refresh) {
        return ApiResponseVO.success(files.directory(pid,user(),path,cursor,refresh));
    }
    @PostMapping("/directories")
    public ApiResponseVO<WorkspaceFileOperationVO> create(@PathVariable Long pid,@jakarta.validation.Valid @RequestBody WorkspaceFileRequestDTO request) {
        return ApiResponseVO.success(files.createDirectory(pid,user(),request));
    }
    @PostMapping(value="/uploads",consumes=MediaType.MULTIPART_FORM_DATA_VALUE)
    public ApiResponseVO<WorkspaceFileOperationVO> upload(@PathVariable Long pid,@RequestParam(defaultValue="") String path,
            @RequestParam String requestKey,@RequestPart("file") MultipartFile file) throws IOException {
        return ApiResponseVO.success(files.upload(pid,user(),path,requestKey,file));
    }
    @PostMapping("/downloads")
    public ApiResponseVO<WorkspaceFileOperationVO> download(@PathVariable Long pid,@jakarta.validation.Valid @RequestBody WorkspaceFileRequestDTO request) {
        return ApiResponseVO.success(files.download(pid,user(),request));
    }
    @GetMapping("/operations/{id}")
    public ApiResponseVO<WorkspaceFileOperationVO> operation(@PathVariable Long pid,@PathVariable Long id) {
        return ApiResponseVO.success(files.operation(pid,user(),id));
    }
    @GetMapping("/operations/{id}/content")
    public ResponseEntity<Resource> content(@PathVariable Long pid,@PathVariable Long id) throws IOException {
        return WorkspaceFileResponses.download(files.downloadContent(pid,user(),id));
    }
    @GetMapping("/operations/{id}/preview")
    public ApiResponseVO<WorkspaceFilePreviewVO> preview(@PathVariable Long pid,@PathVariable Long id) throws IOException {
        return ApiResponseVO.success(previews.preview(pid,user(),id));
    }
    private Long user() {return UserContext.requireCurrentUser().getId();}
}
