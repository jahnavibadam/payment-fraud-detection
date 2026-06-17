package com.frauddetection.scoring;

import com.frauddetection.model.CustomerProfile;
import com.frauddetection.model.FasterPaymentRequest;

public interface ComponentScorer {
    ScorerResult score(FasterPaymentRequest request, CustomerProfile profile);
}
