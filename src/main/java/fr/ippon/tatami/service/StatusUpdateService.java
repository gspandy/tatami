package fr.ippon.tatami.service;

import fr.ippon.tatami.domain.Status;
import fr.ippon.tatami.repository.*;
import fr.ippon.tatami.security.AuthenticationService;
import fr.ippon.tatami.service.util.DomainUtil;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.springframework.stereotype.Service;

import javax.inject.Inject;
import java.util.Collection;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class StatusUpdateService {

    private final Log log = LogFactory.getLog(StatusUpdateService.class);

    private final static Pattern PATTERN_LOGIN = Pattern.compile("@[^\\s]+");

    @Inject
    private FollowerRepository followerRepository;

    @Inject
    private AuthenticationService authenticationService;

    @Inject
    private DaylineRepository daylineRepository;

    @Inject
    private StatusRepository statusRepository;

    @Inject
    private TimelineRepository timelineRepository;

    @Inject
    private UserlineRepository userlineRepository;

    @Inject
    private TaglineRepository taglineRepository;

    @Inject
    private DiscussionRepository discussionRepository;

    @Inject
    private CounterRepository counterRepository;

    @Inject
    private SearchService searchService;

    public void postStatus(String content) {
        createStatus(content, "", "");
    }

    public void replyToStatus(String content, String replyTo) {
        Status originalStatus = statusRepository.findStatusById(replyTo);
        Status replyStatus = createStatus(content, replyTo, originalStatus.getUsername());
        if (!originalStatus.getReplyTo().equals("")) {
            log.debug("Original status is also a reply, replying to the real original status instead.");
            Status realOriginalStatus = statusRepository.findStatusById(originalStatus.getReplyTo());
            discussionRepository.addReplyToDiscussion(realOriginalStatus.getStatusId(), replyStatus.getStatusId());
        } else {
            discussionRepository.addReplyToDiscussion(originalStatus.getStatusId(), replyStatus.getStatusId());
        }
    }

    private Status createStatus(String content, String replyTo, String replyToUsername) {
        if (log.isDebugEnabled()) {
            log.debug("Creating new status : " + content);
        }
        String currentLogin = authenticationService.getCurrentUser().getLogin();
        String username = DomainUtil.getUsernameFromLogin(currentLogin);
        String domain = DomainUtil.getDomainFromLogin(currentLogin);
        Status status =
                statusRepository.createStatus(currentLogin, username, domain, content, replyTo, replyToUsername);

        // add status to the dayline, userline, timeline, tagline
        String day = StatsService.DAYLINE_KEY_FORMAT.format(status.getStatusDate());
        daylineRepository.addStatusToDayline(status, domain, day);
        userlineRepository.addStatusToUserline(status);
        timelineRepository.addStatusToTimeline(currentLogin, status);
        taglineRepository.addStatusToTagline(status, domain);

        // add status to the follower's timelines
        Collection<String> followersForUser = followerRepository.findFollowersForUser(currentLogin);
        for (String followerLogin : followersForUser) {
            timelineRepository.addStatusToTimeline(followerLogin, status);
        }

        // add status to the mentioned users' timeline
        Matcher m = PATTERN_LOGIN.matcher(status.getContent());
        while (m.find()) {
            String mentionedUsername = extractUsernameWithoutAt(m.group());
            if (mentionedUsername != null &&
                    !mentionedUsername.equals(currentLogin) &&
                    !followersForUser.contains(mentionedUsername)) {

                if (log.isDebugEnabled()) {
                    log.debug("Mentionning : " + mentionedUsername);
                }
                String mentionedLogin =
                        DomainUtil.getLoginFromUsernameAndDomain(mentionedUsername, domain);

                timelineRepository.addStatusToTimeline(mentionedLogin, status);
            }
        }

        // Increment status count for the current user
        counterRepository.incrementStatusCounter(currentLogin);

        // Add to the searchStatus engine
        searchService.addStatus(status);

        return status;
    }

    private String extractUsernameWithoutAt(String dest) {
        return dest.substring(1, dest.length());
    }
}
