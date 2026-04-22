package com.authx.testapp.controller;

import static com.authx.testapp.schema.Schema.Folder;
import static com.authx.testapp.schema.Schema.Space;
import static com.authx.testapp.schema.Schema.User;

import com.authx.sdk.AuthxClient;
import com.authx.testapp.dto.ApiDtos.UserRequest;
import com.authx.testapp.dto.ApiDtos.WriteResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Folder hierarchy + ownership endpoints. A folder may live directly
 * under a space ({@link Folder.Rel#SPACE}) or nested under another folder
 * ({@link Folder.Rel#PARENT}) — documents inside inherit viewer / editor
 * permissions through the parent chain.
 */
@RestController
@RequestMapping("/folders/{folderId}")
public class FolderController {

    private final AuthxClient auth;

    public FolderController(AuthxClient auth) {
        this.auth = auth;
    }

    /** Root a folder directly under a space. */
    @PutMapping("/space/{spaceId}")
    public ResponseEntity<Void> linkToSpace(@PathVariable String folderId,
                                             @PathVariable String spaceId) {
        auth.on(Folder).select(folderId)
                .grant(Folder.Rel.SPACE)
                .to(Space, spaceId)
                .commit();
        return ResponseEntity.noContent().build();
    }

    /** Nest a folder under another folder. */
    @PutMapping("/parent/{parentFolderId}")
    public ResponseEntity<Void> linkToParent(@PathVariable String folderId,
                                               @PathVariable String parentFolderId) {
        auth.on(Folder).select(folderId)
                .grant(Folder.Rel.PARENT)
                .to(Folder, parentFolderId)
                .commit();
        return ResponseEntity.noContent().build();
    }

    /** Assign a user as the folder owner. */
    @PostMapping("/owners")
    public ResponseEntity<WriteResponse> addOwner(@PathVariable String folderId,
                                                    @RequestBody UserRequest body) {
        int writes = auth.on(Folder).select(folderId)
                .grant(Folder.Rel.OWNER)
                .to(User, body.userId())
                .commit().count();
        return ResponseEntity.status(HttpStatus.CREATED).body(new WriteResponse(writes));
    }
}
