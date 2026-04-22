package com.authx.testapp.controller;

import static com.authx.testapp.schema.Schema.Organization;
import static com.authx.testapp.schema.Schema.User;

import com.authx.sdk.AuthxClient;
import com.authx.testapp.dto.ApiDtos.UserRequest;
import com.authx.testapp.dto.ApiDtos.UsersRequest;
import com.authx.testapp.dto.ApiDtos.WriteResponse;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Organisation membership endpoints. Admins and regular members are
 * represented by distinct relations ({@code Organization.Rel.ADMIN} vs
 * {@code Organization.Rel.MEMBER}); admin implies member by inheritance
 * in the SpiceDB schema.
 */
@RestController
@RequestMapping("/organizations/{orgId}")
public class OrganizationController {

    private final AuthxClient auth;

    public OrganizationController(AuthxClient auth) {
        this.auth = auth;
    }

    /** Promote a user to organisation admin. */
    @PostMapping("/admins")
    public ResponseEntity<WriteResponse> grantAdmin(@PathVariable String orgId,
                                                     @RequestBody UserRequest body) {
        int writes = auth.on(Organization).select(orgId)
                .grant(Organization.Rel.ADMIN)
                .to(User, body.userId())
                .commit().count();
        return ResponseEntity.status(HttpStatus.CREATED).body(new WriteResponse(writes));
    }

    /** Add one or more users as plain organisation members. */
    @PostMapping("/members")
    public ResponseEntity<WriteResponse> grantMembers(@PathVariable String orgId,
                                                       @RequestBody UsersRequest body) {
        int writes = auth.on(Organization).select(orgId)
                .grant(Organization.Rel.MEMBER)
                .to(User, body.userIds())
                .commit().count();
        return ResponseEntity.status(HttpStatus.CREATED).body(new WriteResponse(writes));
    }
}
