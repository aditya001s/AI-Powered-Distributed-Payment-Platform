package com.aegispay.common.domain;

/**
 * Small shared vocabularies used across service boundaries.
 *
 * <p>Grouped in one file because each is only a handful of constants; keeping
 * them together makes the cross-service contract easy to review as a whole.
 */
public final class SharedEnums {

    private SharedEnums() {
    }

    /** Which side of a double-entry posting a line sits on. */
    public enum PostingDirection {
        DEBIT,
        CREDIT;

        public PostingDirection opposite() {
            return this == DEBIT ? CREDIT : DEBIT;
        }

        /**
         * Sign convention used to prove the debit==credit invariant: debits are
         * positive, credits negative, and a balanced posting sums to exactly zero.
         */
        public int sign() {
            return this == DEBIT ? 1 : -1;
        }
    }

    /**
     * Chart-of-accounts classification.
     *
     * <p>Needed because "increase" means opposite things depending on the account
     * type: a debit increases an asset but decreases a liability. Customer wallet
     * balances are a LIABILITY of the platform (we owe the customer their money),
     * which is the single most commonly mis-modelled thing in payment systems.
     */
    public enum AccountType {
        /** Funds the platform holds at a bank/provider. */
        ASSET,
        /** What the platform owes customers. Wallet balances live here. */
        LIABILITY,
        /** Fees earned. */
        REVENUE,
        /** Provider costs. */
        EXPENSE,
        /** Suspense/clearing accounts used mid-saga. */
        CLEARING;

        /** True when a DEBIT increases this account's balance. */
        public boolean debitIncreases() {
            return this == ASSET || this == EXPENSE || this == CLEARING;
        }
    }

    /** What kind of money movement a payment represents. */
    public enum PaymentType {
        /** Wallet to wallet inside the platform. */
        P2P_TRANSFER,
        /** Customer paying a merchant. */
        MERCHANT_PAYMENT,
        /** Money in. */
        WALLET_TOPUP,
        /** Money out. */
        WALLET_WITHDRAWAL,
        /** Reversal of a completed payment. */
        REFUND
    }

    /** Delivery channel for a notification. */
    public enum NotificationChannel {
        EMAIL,
        SMS,
        PUSH,
        /** Rendered inside the dashboard. */
        IN_APP
    }

    /** How urgently a notification must be delivered. */
    public enum NotificationPriority {
        LOW,
        NORMAL,
        /** Security-relevant: fraud holds, new-device logins. */
        HIGH
    }

    /** Terminal state of a notification delivery attempt. */
    public enum NotificationStatus {
        PENDING,
        SENT,
        FAILED,
        /** Suppressed by user preference or de-duplication. */
        SUPPRESSED
    }

    /** Where a request entered the platform. Used as a fraud feature. */
    public enum Channel {
        WEB,
        MOBILE_ANDROID,
        MOBILE_IOS,
        API,
        UNKNOWN
    }
}
