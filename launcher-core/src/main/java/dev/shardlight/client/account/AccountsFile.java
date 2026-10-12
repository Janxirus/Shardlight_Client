package dev.shardlight.client.account;

import java.util.ArrayList;
import java.util.List;

/** The on-disk shape of {@code accounts.json}. Holds no tokens: those live in the secret store. */
final class AccountsFile {
    int schemaVersion = AccountService.SCHEMA_VERSION;
    /** the account used for launching, or null */
    String selectedId;
    List<Account> accounts = new ArrayList<>();
}
