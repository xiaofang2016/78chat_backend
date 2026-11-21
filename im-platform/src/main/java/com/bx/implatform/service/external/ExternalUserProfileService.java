package com.bx.implatform.service.external;

import com.bx.implatform.dto.external.UserProfileSyncDTO;

public interface ExternalUserProfileService {

    void sync(UserProfileSyncDTO dto);
}
