package com.authx.sdk.e2e;

import com.authx.sdk.AuthxClient;
import com.authx.sdk.exception.AuthxPreconditionException;
import com.authx.sdk.model.*;
import com.authx.sdk.transport.SdkTransport.RelationshipUpdate;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

@Tag("spicedb-e2e")
class SpiceDbAtomicPreconditionE2eTest {
    @Test void conditionsAndUpdatesAreOneAtomicWriteThroughTheWholeSdkStack() {
        try (SpiceDbTestServer server = SpiceDbTestServer.start();
                AuthxClient client = AuthxClient.builder().connection(connection -> connection.target(server.target())
                        .presharedKey(SpiceDbTestServer.PRESHARED_KEY)).build()) {
            client.schema().writeRaw("definition user {}\ndefinition document {\n relation viewer: user\n permission view = viewer\n}");
            RelationshipFilter filter = new RelationshipFilter("document", "atomic", "viewer", "user", null, null);
            RelationshipPrecondition empty = new RelationshipPrecondition(RelationshipPrecondition.Operation.MUST_NOT_MATCH, filter);
            RelationshipUpdate alice = new RelationshipUpdate(RelationshipUpdate.Operation.TOUCH,
                    ResourceRef.of("document", "atomic"), Relation.of("viewer"), SubjectRef.parse("user:alice"));
            WriteResult first = client.write(new WriteRequest(List.of(alice), List.of(empty)));
            assertThat(first.zedToken()).isNotBlank();
            RelationshipUpdate bob = new RelationshipUpdate(RelationshipUpdate.Operation.TOUCH,
                    ResourceRef.of("document", "atomic"), Relation.of("viewer"), SubjectRef.parse("user:bob"));
            assertThatThrownBy(() -> client.write(new WriteRequest(List.of(bob), List.of(empty))))
                    .isInstanceOf(AuthxPreconditionException.class);
            assertThat(client.on("document").select("atomic").check("view").withConsistency(first.asConsistency()).by("user:bob")).isFalse();
            RelationshipPrecondition exists = new RelationshipPrecondition(RelationshipPrecondition.Operation.MUST_MATCH,
                    new RelationshipFilter("document", "atomic", "viewer", "user", "alice", null));
            WriteResult swapped = client.write(new WriteRequest(List.of(new RelationshipUpdate(RelationshipUpdate.Operation.DELETE,
                    alice.resource(), alice.relation(), alice.subject()), bob), List.of(exists)));
            assertThat(client.on("document").select("atomic").check("view").withConsistency(swapped.asConsistency()).by("user:alice")).isFalse();
            assertThat(client.on("document").select("atomic").check("view").withConsistency(swapped.asConsistency()).by("user:bob")).isTrue();
        }
    }
}
