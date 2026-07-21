package com.lop.budget.domain.model

import com.lop.budget.R

enum class DebtType(val labelRes: Int) {
    LOAN(R.string.debt_type_loan),
    CREDIT_CARD(R.string.debt_type_credit_card),
    PERSONAL(R.string.debt_type_personal),
    OTHER(R.string.debt_type_other)
}
