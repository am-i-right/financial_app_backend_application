package com.myfinance.service;

import com.myfinance.model.Wallet;
import java.time.YearMonth;

public interface Report {
    String generate(Wallet wallet, YearMonth month);
}
