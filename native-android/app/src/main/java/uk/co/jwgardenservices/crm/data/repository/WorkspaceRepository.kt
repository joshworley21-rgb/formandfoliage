package uk.co.jwgardenservices.crm.data.repository

import io.github.jan.supabase.SupabaseClient
import io.github.jan.supabase.postgrest.postgrest
import uk.co.jwgardenservices.crm.data.model.WorkspaceRow
import uk.co.jwgardenservices.crm.data.remote.SupabaseProvider

/** Who the signed-in user is working as: themselves, or an employee. */
class WorkspaceRepository(
    private val client: SupabaseClient = SupabaseProvider.client,
) {
    /** Link a pending employee invite to the account that just signed in. */
    suspend fun claimStaffInvite() {
        runCatching { client.postgrest.rpc("claim_staff_invite") }
    }

    suspend fun myWorkspace(): WorkspaceRow? =
        client.postgrest.rpc("my_workspace").decodeList<WorkspaceRow>().firstOrNull()
}
