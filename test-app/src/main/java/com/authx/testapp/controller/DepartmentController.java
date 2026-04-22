package com.authx.testapp.controller;

import static com.authx.testapp.schema.Schema.Department;
import static com.authx.testapp.schema.Schema.User;

import com.authx.sdk.AuthxClient;
import com.authx.testapp.dto.ApiDtos.UsersRequest;
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
 * Department membership + parent-chain endpoints.
 *
 * <p>Departments expose a {@code #all_members} permission that transitively
 * includes every member of every descendant department — granting a
 * viewer/editor relation to {@code department:X#all_members} therefore
 * fans out through the whole sub-tree without per-user wiring.
 */
@RestController
@RequestMapping("/departments/{deptId}")
public class DepartmentController {

    private final AuthxClient auth;

    public DepartmentController(AuthxClient auth) {
        this.auth = auth;
    }

    /** Add one or more direct members to the department. */
    @PostMapping("/members")
    public ResponseEntity<WriteResponse> grantMembers(@PathVariable String deptId,
                                                       @RequestBody UsersRequest body) {
        int writes = auth.on(Department).select(deptId)
                .grant(Department.Rel.MEMBER)
                .to(User, body.userIds())
                .commit().count();
        return ResponseEntity.status(HttpStatus.CREATED).body(new WriteResponse(writes));
    }

    /** Set the department's parent in the org tree. */
    @PutMapping("/parent/{parentDeptId}")
    public ResponseEntity<Void> setParent(@PathVariable String deptId,
                                            @PathVariable String parentDeptId) {
        auth.on(Department).select(deptId)
                .grant(Department.Rel.PARENT)
                .to(Department, parentDeptId)
                .commit().listener(null);
        return ResponseEntity.noContent().build();
    }
}
