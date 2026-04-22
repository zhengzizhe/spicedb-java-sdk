package com.authx.testapp.controller;

import static com.authx.testapp.schema.Schema.Group;
import static com.authx.testapp.schema.Schema.User;

import com.authx.sdk.AuthxClient;
import com.authx.testapp.dto.ApiDtos.UserRequest;
import com.authx.testapp.dto.ApiDtos.WriteResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Cross-functional team endpoints. A group is a flat bag of users; grants
 * to "{@code group:X#member}" as a subject let downstream resources
 * reference the whole team in one tuple.
 */
@RestController
@RequestMapping("/groups/{groupId}")
public class GroupController {

    private final AuthxClient auth;

    public GroupController(AuthxClient auth) {
        this.auth = auth;
    }

    /** Add a user to the group. */
    @PostMapping("/members")
    public ResponseEntity<WriteResponse> addMember(@PathVariable String groupId,
                                                    @RequestBody UserRequest body) {
        int writes = auth.on(Group).select(groupId)
                .grant(Group.Rel.MEMBER)
                .to(User, body.userId())
                .commit().count();
        return ResponseEntity.status(HttpStatus.CREATED).body(new WriteResponse(writes));
    }

    /** Remove a user from the group. */
    @DeleteMapping("/members/{userId}")
    public ResponseEntity<Void> removeMember(@PathVariable String groupId,
                                              @PathVariable String userId) {
        auth.on(Group).select(groupId)
                .revoke(Group.Rel.MEMBER)
                .from(User, userId)
                .commit();
        return ResponseEntity.noContent().build();
    }
}
