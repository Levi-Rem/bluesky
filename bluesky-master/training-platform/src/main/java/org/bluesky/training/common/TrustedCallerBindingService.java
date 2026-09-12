package org.bluesky.training.common;

import org.bluesky.training.persistence.TrustedCallerBindingMapper;
import org.bluesky.training.persistence.TrustedCallerBindingRow;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** P02：证书指纹绑定校验（详细设计 14.7）。 */
@Service
public class TrustedCallerBindingService {

    private final TrustedCallerBindingMapper mapper;

    public TrustedCallerBindingService(TrustedCallerBindingMapper mapper) {
        this.mapper = mapper;
    }

    @Transactional
    public TrustedCallerBindingRow resolveByFingerprintDigest(String digest) {
        TrustedCallerBindingRow row = digest == null ? null : mapper.findByFingerprintDigest(digest);
        if (row == null) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                    "证书指纹未绑定任何终端");
        }
        return row;
    }

    public void assertEnabled(TrustedCallerBindingRow row) {
        if (row == null || !row.isEnabled()) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                    "终端绑定已停用");
        }
    }

    public void assertMatchesTerminal(TrustedCallerBindingRow row, String terminalId) {
        if (row == null || terminalId == null || !terminalId.equals(row.getTerminalId())) {
            throw new V2DomainException("TRUSTED_IDENTITY_REJECTED", 403,
                    "证书绑定与终端不一致");
        }
    }

    @Transactional
    public void touchLastSeen(String bindingId) {
        if (bindingId != null) {
            mapper.touchLastSeen(bindingId);
        }
    }
}
