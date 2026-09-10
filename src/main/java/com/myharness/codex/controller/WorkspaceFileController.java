package com.myharness.codex.controller;

import com.myharness.codex.entity.dto.WorkspaceFileRequestDTO;
import com.myharness.codex.entity.dto.WorkspaceFileActionRequestDTO;
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
    @PostMapping("/renames")
    public ApiResponseVO<WorkspaceFileOperationVO> rename(@PathVariable Long pid,@jakarta.validation.Valid @RequestBody WorkspaceFileActionRequestDTO request) {
        return ApiResponseVO.success(files.rename(pid,user(),request));
    }
    @PostMapping("/moves")
    public ApiResponseVO<WorkspaceFileOperationVO> move(@PathVariable Long pid,@jakarta.validation.Valid @RequestBody WorkspaceFileActionRequestDTO request) {
        return ApiResponseVO.success(files.move(pid,user(),request));
    }
    @PostMapping("/delete-plans")
    public ApiResponseVO<WorkspaceFileOperationVO> deletePlan(@PathVariable Long pid,@jakarta.validation.Valid @RequestBody WorkspaceFileActionRequestDTO request) {
        return ApiResponseVO.success(files.deletePlan(pid,user(),request));
    }
    @PostMapping("/deletions")
    public ApiResponseVO<WorkspaceFileOperationVO> delete(@PathVariable Long pid,@jakarta.validation.Valid @RequestBody WorkspaceFileActionRequestDTO request) {
        return ApiResponseVO.success(files.delete(pid,user(),request));
    }
    @PostMapping("/archive-downloads")
    public ApiResponseVO<WorkspaceFileOperationVO> archive(@PathVariable Long pid,@jakarta.validation.Valid @RequestBody WorkspaceFileActionRequestDTO request) {
        return ApiResponseVO.success(files.archive(pid,user(),request));
    }
    @GetMapping("/operations")
    public ApiResponseVO<WorkspaceFilePageVO<WorkspaceFileOperationVO>> recent(@PathVariable Long pid,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="20") int limit) {
        return ApiResponseVO.success(files.recent(pid,user(),cursor,limit));
    }
    @GetMapping("/operations/{id}/items")
    public ApiResponseVO<WorkspaceFilePageVO<com.myharness.codex.entity.dto.WorkspaceFileItemsDTO.Item>> items(@PathVariable Long pid,@PathVariable Long id,
            @RequestParam(required=false) String cursor,@RequestParam(defaultValue="100") int limit) {
        return ApiResponseVO.success(files.items(pid,user(),id,cursor,limit));
    }
    @PostMapping("/operations/{id}/reconcile")
    public ApiResponseVO<WorkspaceFileOperationVO> reconcile(@PathVariable Long pid,@PathVariable Long id,
            @jakarta.validation.Valid @RequestBody WorkspaceFileActionRequestDTO request) {
        return ApiResponseVO.success(files.reconcile(pid,user(),id,request.requestKey()));
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
