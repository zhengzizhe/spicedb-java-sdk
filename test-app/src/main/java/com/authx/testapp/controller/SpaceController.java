package com.authx.testapp.controller;

import static com.authx.testapp.schema.Schema.Organization;
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
 * Space membership endpoints. Spaces are the top-level container for
 * folders and documents; linking a space to an organisation
 * ({@link Space.Rel#ORG}) delegates admin rights to org admins.
 */
@RestController
@RequestMapping("/spaces/{spaceId}")
public class SpaceController {

    private final AuthxClient auth;

    public SpaceController(AuthxClient auth) {
        this.auth = auth;
    }

    /** Attach a space to an organisation — org admins inherit space admin. */
    @PutMapping("/organization/{orgId}")
    public ResponseEntity<Void> linkOrganization(@PathVariable String spaceId,
                                                   @PathVariable String orgId) {
        auth.on(Space).select(spaceId)
                .grant(Space.Rel.ORG)
                .to(Organization, orgId);
        return ResponseEntity.noContent().build();
    }

    /** Add a user as a direct space member. */
    @PostMapping("/members")
    public ResponseEntity<WriteResponse> addMember(@PathVariable String spaceId,
                                                    @RequestBody UserRequest body) {
        int writes = auth.on(Space).select(spaceId)
                .grant(Space.Rel.MEMBER)
                .to(User, body.userId())
                .result().count();
        return ResponseEntity.status(HttpStatus.CREATED).body(new WriteResponse(writes));
    }
}
