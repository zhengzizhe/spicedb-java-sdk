package com.authx.testapp.schema;

import com.authx.sdk.PermissionProxy;
import com.authx.sdk.ResourceType;
import org.junit.jupiter.api.Test;

import static com.authx.testapp.schema.Schema.*;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Guard against the class-initialization NPE trap documented in the spec:
 * the Schema's Proxy classes must reference enum constants by FQN so the
 * Descriptor + Proxy fields finish initializing before any value is read.
 *
 * <p>If this test ever NPEs, the codegen has regressed and started
 * emitting short-name enum references.
 */
class SchemaInitOrderTest {

    @Test
    void descriptorFieldsAreNonNull() {
        assertThat(Organization).isNotNull();
        assertThat(User).isNotNull();
        assertThat(Department).isNotNull();
        assertThat(Group).isNotNull();
        assertThat(Space).isNotNull();
        assertThat(Folder).isNotNull();
        assertThat(Document).isNotNull();
    }

    @Test
    void descriptorsAreResourceTypes() {
        assertThat(Organization).isInstanceOf(ResourceType.class);
        assertThat(Organization.name()).isEqualTo("organization");
        assertThat(Document.name()).isEqualTo("document");
    }

    @Test
    void relProxyFieldsEqualEnumConstants() {
        // If the proxy used short-name references, this NPEs or returns null.
        assertThat(Organization.Rel.ADMIN)
                .isSameAs(com.authx.testapp.schema.Organization.Rel.ADMIN);
        assertThat(Document.Rel.EDITOR)
                .isSameAs(com.authx.testapp.schema.Document.Rel.EDITOR);
    }

    @Test
    void permProxyImplementsPermissionProxyWithCorrectClass() {
        PermissionProxy<com.authx.testapp.schema.Document.Perm> proxy = Document.Perm;
        assertThat(proxy.enumClass()).isEqualTo(com.authx.testapp.schema.Document.Perm.class);
    }

    @Test
    void emptyEnumsStillHaveValidProxies() {
        // User has no relations and no permissions — the Proxy classes still
        // exist with zero fields, and the Descriptor is still a valid
        // ResourceType<User.Rel, User.Perm>.
        assertThat(User.name()).isEqualTo("user");
        assertThat(User.Rel).isNotNull();
        assertThat(User.Perm).isNotNull();
        assertThat(User.Perm.enumClass()).isEqualTo(com.authx.testapp.schema.User.Perm.class);
    }
}
