package com.aegispay.payment.repository;

import com.aegispay.payment.domain.PaymentRefundRecord;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRefundRepository extends JpaRepository<PaymentRefundRecord, String> {
}
