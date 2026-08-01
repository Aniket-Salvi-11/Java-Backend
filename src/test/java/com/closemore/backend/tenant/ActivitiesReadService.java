package com.closemore.backend.tenant;

import com.closemore.backend.domain.ActivityAttachmentEntity;
import com.closemore.backend.domain.ActivityEntity;
import com.closemore.backend.repository.ActivityAttachmentRepository;
import com.closemore.backend.repository.ActivityRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

/** Test-only. Mirrors DealsReadService; see that class for why reads go through a service. */
@Service
@Transactional(readOnly = true)
@RequiredArgsConstructor
public class ActivitiesReadService {

    private final ActivityRepository activities;
    private final ActivityAttachmentRepository attachments;

    public List<ActivityEntity> allActivities() {
        return activities.findAll();
    }

    public Optional<ActivityEntity> activityById(String logId) {
        return activities.findById(logId);
    }

    public List<ActivityEntity> activitiesForParent(String type, String id) {
        return activities.findByParentObjectTypeAndParentObjectId(type, id);
    }

    public List<ActivityAttachmentEntity> allAttachments() {
        return attachments.findAll();
    }

    public List<ActivityAttachmentEntity> attachmentsForLog(String logId) {
        return attachments.findByLogId(logId);
    }
}
