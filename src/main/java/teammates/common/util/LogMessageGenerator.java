package teammates.common.util;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import teammates.common.datatransfer.UserType;
import teammates.common.datatransfer.attributes.AccountAttributes;
import teammates.common.datatransfer.attributes.StudentAttributes;
import teammates.common.exception.TeammatesException;
import teammates.common.util.ActivityLogEntry.Builder;

/**
 * Factory to generate log messages.
 */
public class LogMessageGenerator {
    public static final Pattern PATTERN_ACTION_NAME = Pattern.compile("^/\\S+?/(?<actionName>[^\\s\\?]*)");
    public static final String PATTERN_ACTION_NAME_GROUP = "actionName";

    private static final DateTimeFormatter FORMATTER =
            TeammatesDateTimeFormatter.ofPattern(Const.ActivityLog.TIME_FORMAT_LOGID);

    /**
     * Generates the log message for an *Action.
     *
     * @param url URL of the action
     * @param params parameterMap of the request
     * @param currUser login information generated by {@link teammates.logic.api.GateKeeper}
     * @param userAccount authentication user account generated by action
     * @param unregisteredStudent authentication unregisteredStudent attributes generated by action
     * @param logMessage log message to show to admin
     * @return log message in the form specified in {@link ActivityLogEntry}
     */
    public String generatePageActionLogMessage(String url, Map<String, String[]> params, UserType currUser,
            AccountAttributes userAccount, StudentAttributes unregisteredStudent, String logMessage) {
        Builder builder = generateBasicLogEntryBuilder(url, params, currUser);

        boolean isUnregisteredStudent = unregisteredStudent != null;
        boolean isAccountWithGoogleId = userAccount != null && userAccount.googleId != null;
        if (isUnregisteredStudent) {
            updateInfoForUnregisteredStudent(builder, unregisteredStudent);
        } else if (isAccountWithGoogleId) {
            updateInfoForNormalUser(builder, currUser, userAccount);
        }

        builder.withLogMessage(logMessage);
        return builder.build().generateLogMessage();
    }

    private void updateInfoForUnregisteredStudent(Builder builder, StudentAttributes unregisteredStudent) {
        String role = Const.ActivityLog.ROLE_UNREGISTERED;
        if (unregisteredStudent.course != null && !unregisteredStudent.course.isEmpty()) {
            role = Const.ActivityLog.ROLE_UNREGISTERED + ":" + unregisteredStudent.course;
        }
        builder.withUserRole(role)
               .withUserName(unregisteredStudent.name)
               .withUserEmail(unregisteredStudent.email);
    }

    private void updateInfoForNormalUser(Builder builder, UserType currUser, AccountAttributes userAccount) {
        checkAndUpdateForMasqueradeMode(builder, currUser, userAccount);
        builder.withUserGoogleId(userAccount.googleId)
               .withUserEmail(userAccount.email)
               .withUserName(userAccount.name);
    }

    private void checkAndUpdateForMasqueradeMode(Builder builder, UserType loggedInUser, AccountAttributes account) {
        if (loggedInUser != null && loggedInUser.id != null && account != null) {
            boolean isMasqueradeMode = !loggedInUser.id.equals(account.googleId);
            builder.withMasqueradeUserRole(isMasqueradeMode);
        }
    }

    /**
     * Generates log message for servlet action failure.
     *
     * @param url URL of the request
     * @param params parameterMap of the request
     * @param e Exception thrown in the failure
     * @param currUser login information generated by {@link teammates.logic.api.GateKeeper}
     * @return log message in the form specified in {@link ActivityLogEntry}
     */
    public String generateActionFailureLogMessage(String url, Map<String, String[]> params,
            Exception e, UserType currUser) {
        Builder builder = generateBasicLogEntryBuilder(url, params, currUser);

        String message = "<span class=\"text-danger\">Servlet Action failure in "
                         + builder.getActionName() + "<br>"
                         + e.getClass() + ": " + TeammatesException.toStringWithStackTrace(e) + "<br>"
                         + JsonUtils.toJson(params, Map.class) + "</span>";
        builder.withLogMessage(message);

        builder.withActionResponse(Const.ACTION_RESULT_FAILURE);

        return builder.build().generateLogMessage();
    }

    /**
     * Generates log message with basic information.
     *
     * @param url URL of the request
     * @param params parameterMap of the request
     * @param message log message to show to admin
     * @param currUser login information generated by {@link teammates.logic.api.GateKeeper}
     * @return log message in form specified in {@link ActivityLogEntry}
     */
    public String generateBasicActivityLogMessage(String url, Map<String, String[]> params, String message,
            UserType currUser) {
        Builder builder = generateBasicLogEntryBuilder(url, params, currUser);

        builder.withLogMessage(message);

        return builder.build().generateLogMessage();
    }

    /**
     * Generates a basic builder for activityLogEntry.
     *
     * @param url URL of the request
     * @param params parameterMap of the request
     * @param currUser login information generated by {@link teammates.logic.api.GateKeeper}
     * @return Builder builder with basic information
     */
    private Builder generateBasicLogEntryBuilder(String url, Map<String, String[]> params, UserType currUser) {
        String actionName = getActionNameFromUrl(url);
        long currTime = System.currentTimeMillis();
        Builder builder = new Builder(actionName, url, currTime);

        if (isAutomatedAction(url)) {
            builder.withLogId(generateLogIdForAutomatedAction(currTime))
                    .withUserRole(Const.ActivityLog.ROLE_AUTO);
        } else if (currUser == null) {
            builder.withLogId(generateLogIdWithoutGoogleId(params, currTime))
                    .withUserGoogleId(Const.ActivityLog.AUTH_NOT_LOGIN);
        } else {
            builder.withLogId(generateLogIdWithGoogleId(currUser.id, currTime))
                    .withUserGoogleId(currUser.id);
            updateRoleForLoggedInUser(builder, currUser);
        }

        return builder;
    }

    private void updateRoleForLoggedInUser(Builder builder, UserType currUser) {
        if (currUser.isAdmin) {
            builder.withUserRole(Const.ActivityLog.ROLE_ADMIN);
            downgradeRoleToStudentIfNecessary(builder);
            downgradeRoleToInstructorIfNecessary(builder);
        } else if (currUser.isInstructor && currUser.isStudent) {
            builder.withUserRole(Const.ActivityLog.ROLE_INSTRUCTOR);
            downgradeRoleToStudentIfNecessary(builder);
        } else if (currUser.isStudent) {
            builder.withUserRole(Const.ActivityLog.ROLE_STUDENT);
        } else if (currUser.isInstructor) {
            builder.withUserRole(Const.ActivityLog.ROLE_INSTRUCTOR);
        } else {
            builder.withUserRole(Const.ActivityLog.ROLE_UNREGISTERED);
        }
    }

    private void downgradeRoleToStudentIfNecessary(Builder builder) {
        if (isStudentPage(builder.getActionName())) {
            builder.withUserRole(Const.ActivityLog.ROLE_STUDENT);
        }
    }

    private void downgradeRoleToInstructorIfNecessary(Builder builder) {
        if (isInstructorPage(builder.getActionName())) {
            builder.withUserRole(Const.ActivityLog.ROLE_INSTRUCTOR);
        }
    }

    private boolean isInstructorPage(String actionName) {
        return actionName.toLowerCase().startsWith(Const.ActivityLog.PREFIX_INSTRUCTOR_PAGE)
                || Const.ActionURIs.INSTRUCTOR_FEEDBACK_STATS_PAGE.contains(actionName)
                || Const.ActionURIs.INSTRUCTOR_COURSE_STATS_PAGE.contains(actionName);
    }

    private boolean isStudentPage(String actionName) {
        return actionName.toLowerCase().startsWith(Const.ActivityLog.PREFIX_STUDENT_PAGE);
    }

    private boolean isAutomatedAction(String actionName) {
        return actionName.startsWith(Const.ActivityLog.PREFIX_AUTO_PAGE);
    }

    private String getActionNameFromUrl(String requestUrl) {
        Matcher m = PATTERN_ACTION_NAME.matcher(requestUrl);
        return m.find() ? m.group(PATTERN_ACTION_NAME_GROUP)
                        : String.format(Const.ActivityLog.MESSAGE_ERROR_ACTION_NAME, requestUrl);
    }

    private String generateLogIdForAutomatedAction(long time) {
        return String.join(Const.ActivityLog.FIELD_CONNECTOR,
                Const.ActivityLog.ROLE_AUTO, formatTimeForId(Instant.ofEpochMilli(time)));
    }

    private String generateLogIdWithoutGoogleId(Map<String, String[]> params, long time) {
        String courseId = HttpRequestHelper.getValueFromParamMap(params, Const.ParamsNames.COURSE_ID);
        String studentEmail = HttpRequestHelper.getValueFromParamMap(params, Const.ParamsNames.STUDENT_EMAIL);
        if (courseId != null && studentEmail != null) {
            return String.join(Const.ActivityLog.FIELD_CONNECTOR,
                    studentEmail, courseId, formatTimeForId(Instant.ofEpochMilli(time)));
        }
        return String.join(Const.ActivityLog.FIELD_CONNECTOR,
                Const.ActivityLog.AUTH_NOT_LOGIN, formatTimeForId(Instant.ofEpochMilli(time)));
    }

    private String generateLogIdWithGoogleId(String googleId, long time) {
        return String.join(Const.ActivityLog.FIELD_CONNECTOR, googleId, formatTimeForId(Instant.ofEpochMilli(time)));
    }

    private static String formatTimeForId(Instant instant) {
        return FORMATTER.format(TimeHelper.convertInstantToLocalDateTime(instant, Const.SystemParams.ADMIN_TIME_ZONE));
    }
}
