/*
 * Copyright (c) 2012-2016, b3log.org & hacpai.com & fangstar.com
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.b3log.symphony.processor;

import com.qiniu.util.Auth;
import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Set;
import javax.inject.Inject;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import javax.servlet.http.HttpSession;
import org.apache.commons.lang.ArrayUtils;
import org.apache.commons.lang.StringUtils;
import org.apache.commons.lang.time.DateFormatUtils;
import org.b3log.latke.Keys;
import org.b3log.latke.Latkes;
import org.b3log.latke.logging.Level;
import org.b3log.latke.logging.Logger;
import org.b3log.latke.model.Pagination;
import org.b3log.latke.model.Role;
import org.b3log.latke.model.User;
import org.b3log.latke.service.LangPropsService;
import org.b3log.latke.service.ServiceException;
import org.b3log.latke.servlet.HTTPRequestContext;
import org.b3log.latke.servlet.HTTPRequestMethod;
import org.b3log.latke.servlet.annotation.After;
import org.b3log.latke.servlet.annotation.Before;
import org.b3log.latke.servlet.annotation.RequestProcessing;
import org.b3log.latke.servlet.annotation.RequestProcessor;
import org.b3log.latke.servlet.renderer.freemarker.AbstractFreeMarkerRenderer;
import org.b3log.latke.util.Paginator;
import org.b3log.latke.util.Requests;
import org.b3log.latke.util.Strings;
import org.b3log.symphony.model.Article;
import org.b3log.symphony.model.Comment;
import org.b3log.symphony.model.Common;
import org.b3log.symphony.model.Pointtransfer;
import org.b3log.symphony.model.Reward;
import org.b3log.symphony.model.Tag;
import org.b3log.symphony.model.UserExt;
import org.b3log.symphony.model.Vote;
import org.b3log.symphony.processor.advice.AnonymousViewCheck;
import org.b3log.symphony.processor.advice.CSRFCheck;
import org.b3log.symphony.processor.advice.CSRFToken;
import org.b3log.symphony.processor.advice.LoginCheck;
import org.b3log.symphony.processor.advice.stopwatch.StopwatchEndAdvice;
import org.b3log.symphony.processor.advice.stopwatch.StopwatchStartAdvice;
import org.b3log.symphony.processor.advice.validate.ArticleAddValidation;
import org.b3log.symphony.processor.advice.validate.ArticleUpdateValidation;
import org.b3log.symphony.service.ArticleMgmtService;
import org.b3log.symphony.service.ArticleQueryService;
import org.b3log.symphony.service.CommentQueryService;
import org.b3log.symphony.service.FollowQueryService;
import org.b3log.symphony.service.JournalQueryService;
import org.b3log.symphony.service.RewardQueryService;
import org.b3log.symphony.service.ShortLinkQueryService;
import org.b3log.symphony.service.UserQueryService;
import org.b3log.symphony.service.VoteQueryService;
import org.b3log.symphony.util.Emotions;
import org.b3log.symphony.util.Filler;
import org.b3log.symphony.util.Markdowns;
import org.b3log.symphony.util.Sessions;
import org.b3log.symphony.util.Symphonys;
import org.json.JSONObject;
import org.jsoup.Jsoup;
import org.jsoup.safety.Whitelist;

/**
 * Article processor.
 *
 * <ul>
 * <li>Shows an article (/article/{articleId}), GET</li>
 * <li>Shows article pre adding form page (/pre-post), GET</li>
 * <li>Shows article adding form page (/post), GET</li>
 * <li>Adds an article (/post) <em>locally</em>, POST</li>
 * <li>Shows an article updating form page (/update) <em>locally</em>, GET</li>
 * <li>Updates an article (/article/{id}) <em>locally</em>, PUT</li>
 * <li>Markdowns text (/markdown), POST</li>
 * <li>Rewards an article (/article/reward), POST</li>
 * <li>Gets an article preview content (/article/{articleId}/preview), GET</li>
 * </ul>
 *
 * <p>
 * The '<em>locally</em>' means user post an article on Symphony directly rather than receiving an article from
 * externally (for example Rhythm).
 * </p>
 *
 * @author <a href="http://88250.b3log.org">Liang Ding</a>
 * @version 2.14.11.27, Nov 3, 2016
 * @since 0.2.0
 */
@RequestProcessor
public class ArticleProcessor {

    /**
     * Logger.
     */
    private static final Logger LOGGER = Logger.getLogger(ArticleProcessor.class.getName());

    /**
     * Short link query service.
     */
    @Inject
    private ShortLinkQueryService shortLinkQueryService;

    /**
     * Article management service.
     */
    @Inject
    private ArticleMgmtService articleMgmtService;

    /**
     * Article query service.
     */
    @Inject
    private ArticleQueryService articleQueryService;

    /**
     * Comment query service.
     */
    @Inject
    private CommentQueryService commentQueryService;

    /**
     * User query service.
     */
    @Inject
    private UserQueryService userQueryService;

    /**
     * Language service.
     */
    @Inject
    private LangPropsService langPropsService;

    /**
     * Follow query service.
     */
    @Inject
    private FollowQueryService followQueryService;

    /**
     * Reward query service.
     */
    @Inject
    private RewardQueryService rewardQueryService;

    /**
     * Vote query service.
     */
    @Inject
    private VoteQueryService voteQueryService;

    /**
     * Journal query service.
     */
    @Inject
    private JournalQueryService journalQueryService;

    /**
     * Filler.
     */
    @Inject
    private Filler filler;

    /**
     * Shows pre-add article.
     *
     * @param context the specified context
     * @param request the specified request
     * @param response the specified response
     * @throws Exception exception
     */
    @RequestProcessing(value = "/pre-post", method = HTTPRequestMethod.GET)
    @Before(adviceClass = {StopwatchStartAdvice.class, LoginCheck.class})
    @After(adviceClass = {CSRFToken.class, StopwatchEndAdvice.class})
    public void showPreAddArticle(final HTTPRequestContext context, final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        final AbstractFreeMarkerRenderer renderer = new SkinRenderer();
        context.setRenderer(renderer);

        renderer.setTemplateName("/home/pre-post.ftl");
        final Map<String, Object> dataModel = renderer.getDataModel();

        dataModel.put(Common.BROADCAST_POINT, Pointtransfer.TRANSFER_SUM_C_ADD_ARTICLE_BROADCAST);

        filler.fillHeaderAndFooter(request, response, dataModel);
    }

    /**
     * Shows add article.
     *
     * @param context the specified context
     * @param request the specified request
     * @param response the specified response
     * @throws Exception exception
     */
    @RequestProcessing(value = "/post", method = HTTPRequestMethod.GET)
    @Before(adviceClass = {StopwatchStartAdvice.class, LoginCheck.class})
    @After(adviceClass = {CSRFToken.class, StopwatchEndAdvice.class})
    public void showAddArticle(final HTTPRequestContext context, final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        final AbstractFreeMarkerRenderer renderer = new SkinRenderer();
        context.setRenderer(renderer);

        renderer.setTemplateName("/home/post.ftl");
        final Map<String, Object> dataModel = renderer.getDataModel();

        // Qiniu file upload authenticate
        final Auth auth = Auth.create(Symphonys.get("qiniu.accessKey"), Symphonys.get("qiniu.secretKey"));
        final String uploadToken = auth.uploadToken(Symphonys.get("qiniu.bucket"));
        dataModel.put("qiniuUploadToken", uploadToken);
        dataModel.put("qiniuDomain", Symphonys.get("qiniu.domain"));

        if (!Symphonys.getBoolean("qiniu.enabled")) {
            dataModel.put("qiniuUploadToken", "");
        }

        final JSONObject currentUser = (JSONObject) request.getAttribute(User.USER);

        String tags = request.getParameter(Tag.TAGS);
        if (StringUtils.isBlank(tags)) {
            tags = "";

            dataModel.put(Tag.TAGS, tags);
        } else {
            tags = articleMgmtService.formatArticleTags(tags);
            final String[] tagTitles = tags.split(",");

            final StringBuilder tagBuilder = new StringBuilder();
            for (final String title : tagTitles) {
                final String tagTitle = title.trim();

                if (Strings.isEmptyOrNull(tagTitle)) {
                    continue;
                }

                if (!Tag.TAG_TITLE_PATTERN.matcher(tagTitle).matches()) {
                    continue;
                }

                if (Strings.isEmptyOrNull(tagTitle) || tagTitle.length() > Tag.MAX_TAG_TITLE_LENGTH || tagTitle.length() < 1) {
                    continue;
                }

                if (!Role.ADMIN_ROLE.equals(currentUser.optString(User.USER_ROLE))
                        && ArrayUtils.contains(Symphonys.RESERVED_TAGS, tagTitle)) {
                    continue;
                }

                tagBuilder.append(tagTitle).append(",");
            }
            if (tagBuilder.length() > 0) {
                tagBuilder.deleteCharAt(tagBuilder.length() - 1);
            }

            dataModel.put(Tag.TAGS, tagBuilder.toString());
        }

        final String type = request.getParameter(Common.TYPE);
        if (StringUtils.isBlank(type)) {
            dataModel.put(Article.ARTICLE_TYPE, Article.ARTICLE_TYPE_C_NORMAL);
        } else {
            int articleType = Article.ARTICLE_TYPE_C_NORMAL;

            try {
                articleType = Integer.valueOf(type);
            } catch (final Exception e) {
                LOGGER.log(Level.WARN, "Gets article type error [" + type + "]", e);
            }

            if (Article.isInvalidArticleType(articleType)) {
                articleType = Article.ARTICLE_TYPE_C_NORMAL;
            }

            dataModel.put(Article.ARTICLE_TYPE, articleType);
        }

        // Default title for journal
        if (Article.ARTICLE_TYPE_C_JOURNAL_PARAGRAPH == (Integer) dataModel.get(Article.ARTICLE_TYPE)) {
            dataModel.put(Article.ARTICLE_TITLE,
                    currentUser.optString(UserExt.USER_REAL_NAME) + " "
                    + DateFormatUtils.format(System.currentTimeMillis(), "yyyy-MM-dd"));
        }

        final String at = request.getParameter(Common.AT);
        if (StringUtils.isNotBlank(at)) {
            dataModel.put(Common.AT, at);
        }

        filler.fillHeaderAndFooter(request, response, dataModel);
    }

    /**
     * Shows article with the specified article id.
     *
     * @param context the specified context
     * @param request the specified request
     * @param response the specified response
     * @param articleId the specified article id
     * @throws Exception exception
     */
    @RequestProcessing(value = "/article/{articleId}", method = HTTPRequestMethod.GET)
    @Before(adviceClass = {StopwatchStartAdvice.class, AnonymousViewCheck.class})
    @After(adviceClass = {CSRFToken.class, StopwatchEndAdvice.class})
    public void showArticle(final HTTPRequestContext context, final HttpServletRequest request, final HttpServletResponse response,
            final String articleId) throws Exception {
        final AbstractFreeMarkerRenderer renderer = new SkinRenderer();
        context.setRenderer(renderer);

        renderer.setTemplateName("/article.ftl");
        final Map<String, Object> dataModel = renderer.getDataModel();

        final JSONObject article = articleQueryService.getArticleById(articleId);
        if (null == article) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);

            return;
        }

        final HttpSession session = request.getSession(false);
        if (null != session) {
            session.setAttribute(Article.ARTICLE_T_ID, articleId);
        }

        filler.fillHeaderAndFooter(request, response, dataModel);

        final String authorEmail = article.optString(Article.ARTICLE_AUTHOR_EMAIL);
        final JSONObject author = userQueryService.getUserByEmail(authorEmail);
        article.put(Article.ARTICLE_T_AUTHOR_NAME, author.optString(User.USER_NAME));
        article.put(Article.ARTICLE_T_AUTHOR_URL, author.optString(User.USER_URL));
        article.put(Article.ARTICLE_T_AUTHOR_INTRO, author.optString(UserExt.USER_INTRO));
        dataModel.put(Article.ARTICLE, article);

        article.put(Common.IS_MY_ARTICLE, false);
        article.put(Article.ARTICLE_T_AUTHOR, author);
        article.put(Common.REWARDED, false);

        articleQueryService.processArticleContent(article, request);

        switch (article.optInt(Article.ARTICLE_TYPE)) {
            case Article.ARTICLE_TYPE_C_JOURNAL_SECTION: {
                final List<JSONObject> teams = journalQueryService.getSection(article.optLong(Keys.OBJECT_ID));
                dataModel.put(Common.TEAMS, teams);

                break;
            }

            case Article.ARTICLE_TYPE_C_JOURNAL_CHAPTER: {
                final List<JSONObject> teams = journalQueryService.getChapter(article.optLong(Keys.OBJECT_ID));
                dataModel.put(Common.TEAMS, teams);

                break;
            }

            default:
                filler.fillRelevantArticles(dataModel, article);
                filler.fillRandomArticles(dataModel);
                filler.fillHotArticles(dataModel);

                break;
        }

        final boolean isLoggedIn = (Boolean) dataModel.get(Common.IS_LOGGED_IN);
        JSONObject currentUser;
        String currentUserId = null;
        if (isLoggedIn) {
            currentUser = (JSONObject) dataModel.get(Common.CURRENT_USER);
            currentUserId = currentUser.optString(Keys.OBJECT_ID);

            article.put(Common.IS_MY_ARTICLE, currentUserId.equals(article.optString(Article.ARTICLE_AUTHOR_ID)));

            final boolean isFollowing = followQueryService.isFollowing(currentUserId, articleId);
            dataModel.put(Common.IS_FOLLOWING, isFollowing);

            final int vote = voteQueryService.isVoted(currentUserId, articleId);
            dataModel.put(Vote.VOTE, vote);

            if (currentUserId.equals(author.optString(Keys.OBJECT_ID))) {
                article.put(Common.REWARDED, true);
            } else {
                article.put(Common.REWARDED,
                        rewardQueryService.isRewarded(currentUserId, articleId, Reward.TYPE_C_ARTICLE));
            }
        }

        if (!(Boolean) request.getAttribute(Keys.HttpRequest.IS_SEARCH_ENGINE_BOT)) {
            articleMgmtService.incArticleViewCount(articleId);
        }

        // Qiniu file upload authenticate
        final Auth auth = Auth.create(Symphonys.get("qiniu.accessKey"), Symphonys.get("qiniu.secretKey"));
        final String uploadToken = auth.uploadToken(Symphonys.get("qiniu.bucket"));
        dataModel.put("qiniuUploadToken", uploadToken);
        dataModel.put("qiniuDomain", Symphonys.get("qiniu.domain"));

        if (!Symphonys.getBoolean("qiniu.enabled")) {
            dataModel.put("qiniuUploadToken", "");
        }

        dataModel.put(Common.DISCUSSION_VIEWABLE, article.optBoolean(Common.DISCUSSION_VIEWABLE));
        if (!article.optBoolean(Common.DISCUSSION_VIEWABLE)) {
            article.put(Article.ARTICLE_T_COMMENTS, (Object) Collections.emptyList());

            return;
        }

        String pageNumStr = request.getParameter("p");
        if (Strings.isEmptyOrNull(pageNumStr) || !Strings.isNumeric(pageNumStr)) {
            pageNumStr = "1";
        }

        final int pageNum = Integer.valueOf(pageNumStr);
        final int pageSize = Symphonys.getInt("articleCommentsPageSize");
        final int windowSize = Symphonys.getInt("articleCommentsWindowSize");

        final List<JSONObject> articleComments = commentQueryService.getArticleComments(articleId, pageNum, pageSize);
        article.put(Article.ARTICLE_T_COMMENTS, (Object) articleComments);

        // Fill reward(thank)
        for (final JSONObject comment : articleComments) {
            String thankTemplate = langPropsService.get("thankConfirmLabel");
            thankTemplate = thankTemplate.replace("{point}", String.valueOf(Symphonys.getInt("pointThankComment")))
                    .replace("{user}", comment.optJSONObject(Comment.COMMENT_T_COMMENTER).optString(User.USER_NAME));
            comment.put(Comment.COMMENT_T_THANK_LABEL, thankTemplate);

            final String commentId = comment.optString(Keys.OBJECT_ID);
            if (isLoggedIn) {
                comment.put(Common.REWARDED,
                        rewardQueryService.isRewarded(currentUserId, commentId, Reward.TYPE_C_COMMENT));
            }

            comment.put(Common.REWARED_COUNT, rewardQueryService.rewardedCount(commentId, Reward.TYPE_C_COMMENT));
        }

        final int commentCnt = article.getInt(Article.ARTICLE_COMMENT_CNT);
        final int pageCount = (int) Math.ceil((double) commentCnt / (double) pageSize);

        final List<Integer> pageNums = Paginator.paginate(pageNum, pageSize, pageCount, windowSize);
        if (!pageNums.isEmpty()) {
            dataModel.put(Pagination.PAGINATION_FIRST_PAGE_NUM, pageNums.get(0));
            dataModel.put(Pagination.PAGINATION_LAST_PAGE_NUM, pageNums.get(pageNums.size() - 1));
        }

        dataModel.put(Pagination.PAGINATION_CURRENT_PAGE_NUM, pageNum);
        dataModel.put(Pagination.PAGINATION_PAGE_COUNT, pageCount);
        dataModel.put(Pagination.PAGINATION_PAGE_NUMS, pageNums);
        dataModel.put(Common.ARTICLE_COMMENTS_PAGE_SIZE, pageSize);
    }

    /**
     * Adds an article locally.
     *
     * <p>
     * The request json object (an article):
     * <pre>
     * {
     *   "articleTitle": "",
     *   "articleTags": "", // Tags spliting by ','
     *   "articleContent": "",
     *   "articleCommentable": boolean,
     *   "articleType": int,
     *   "articleRewardContent": "",
     *   "articleRewardPoint": int
     * }
     * </pre>
     * </p>
     *
     * @param context the specified context
     * @param request the specified request
     * @param response the specified response
     * @throws IOException io exception
     * @throws ServletException servlet exception
     */
    @RequestProcessing(value = "/article", method = HTTPRequestMethod.POST)
    @Before(adviceClass = {StopwatchStartAdvice.class, LoginCheck.class, CSRFCheck.class, ArticleAddValidation.class})
    @After(adviceClass = StopwatchEndAdvice.class)
    public void addArticle(final HTTPRequestContext context, final HttpServletRequest request, final HttpServletResponse response)
            throws IOException, ServletException {
        context.renderJSON();

        final JSONObject requestJSONObject = (JSONObject) request.getAttribute(Keys.REQUEST);

        final String articleTitle = requestJSONObject.optString(Article.ARTICLE_TITLE);
        String articleTags = requestJSONObject.optString(Article.ARTICLE_TAGS);
        final String articleContent = requestJSONObject.optString(Article.ARTICLE_CONTENT);
        //final boolean articleCommentable = requestJSONObject.optBoolean(Article.ARTICLE_COMMENTABLE);
        final boolean articleCommentable = true;
        final int articleType = requestJSONObject.optInt(Article.ARTICLE_TYPE, Article.ARTICLE_TYPE_C_NORMAL);
        final String articleRewardContent = requestJSONObject.optString(Article.ARTICLE_REWARD_CONTENT);
        final int articleRewardPoint = requestJSONObject.optInt(Article.ARTICLE_REWARD_POINT);
        final String ip = Requests.getRemoteAddr(request);

        final JSONObject article = new JSONObject();
        article.put(Article.ARTICLE_TITLE, articleTitle);
        article.put(Article.ARTICLE_CONTENT, articleContent);
        article.put(Article.ARTICLE_EDITOR_TYPE, 0);
        article.put(Article.ARTICLE_COMMENTABLE, articleCommentable);
        article.put(Article.ARTICLE_TYPE, articleType);
        article.put(Article.ARTICLE_REWARD_CONTENT, articleRewardContent);
        article.put(Article.ARTICLE_REWARD_POINT, articleRewardPoint);
        article.put(Article.ARTICLE_IP, "");
        if (StringUtils.isNotBlank(ip)) {
            article.put(Article.ARTICLE_IP, ip);
        }

        try {
            final JSONObject currentUser = (JSONObject) request.getAttribute(User.USER);

            article.put(Article.ARTICLE_AUTHOR_ID, currentUser.optString(Keys.OBJECT_ID));

            final String authorEmail = currentUser.optString(User.USER_EMAIL);
            article.put(Article.ARTICLE_AUTHOR_EMAIL, authorEmail);

            if (!Role.ADMIN_ROLE.equals(currentUser.optString(User.USER_ROLE))) {
                articleTags = articleMgmtService.filterReservedTags(articleTags);
            }

            if (Strings.isEmptyOrNull(articleTags)) {
                throw new ServiceException(langPropsService.get("articleTagReservedLabel"));
            }

            article.put(Article.ARTICLE_TAGS, articleTags);

            articleMgmtService.addArticle(article);

            context.renderTrueResult();
        } catch (final ServiceException e) {
            final String msg = e.getMessage();
            LOGGER.log(Level.ERROR, "Adds article[title=" + articleTitle + "] failed: {0}", e.getMessage());

            context.renderMsg(msg);
        }
    }

    /**
     * Shows update article.
     *
     * @param context the specified context
     * @param request the specified request
     * @param response the specified response
     * @throws Exception exception
     */
    @RequestProcessing(value = "/update", method = HTTPRequestMethod.GET)
    @Before(adviceClass = {StopwatchStartAdvice.class, LoginCheck.class})
    @After(adviceClass = {CSRFToken.class, StopwatchEndAdvice.class})
    public void showUpdateArticle(final HTTPRequestContext context, final HttpServletRequest request, final HttpServletResponse response)
            throws Exception {
        final String articleId = request.getParameter("id");
        if (Strings.isEmptyOrNull(articleId)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);

            return;
        }

        final JSONObject article = articleQueryService.getArticleById(articleId);
        if (null == article) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);

            return;
        }

        final JSONObject currentUser = Sessions.currentUser(request);
        if (null == currentUser
                || !currentUser.optString(Keys.OBJECT_ID).equals(article.optString(Article.ARTICLE_AUTHOR_ID))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);

            return;
        }

        final AbstractFreeMarkerRenderer renderer = new SkinRenderer();
        context.setRenderer(renderer);

        renderer.setTemplateName("/home/post.ftl");
        final Map<String, Object> dataModel = renderer.getDataModel();

        dataModel.put(Article.ARTICLE, article);

        filler.fillHeaderAndFooter(request, response, dataModel);

        // Qiniu file upload authenticate
        final Auth auth = Auth.create(Symphonys.get("qiniu.accessKey"), Symphonys.get("qiniu.secretKey"));
        final String uploadToken = auth.uploadToken(Symphonys.get("qiniu.bucket"));
        dataModel.put("qiniuUploadToken", uploadToken);
        dataModel.put("qiniuDomain", Symphonys.get("qiniu.domain"));

        if (!Symphonys.getBoolean("qiniu.enabled")) {
            dataModel.put("qiniuUploadToken", "");
        }
    }

    /**
     * Updates an article locally.
     *
     * <p>
     * The request json object (an article):
     * <pre>
     * {
     *   "articleTitle": "",
     *   "articleTags": "", // Tags spliting by ','
     *   "articleContent": "",
     *   "articleCommentable": boolean,
     *   "articleType": int,
     *   "articleRewardContent": "",
     *   "articleRewardPoint": int
     * }
     * </pre>
     * </p>
     *
     * @param context the specified context
     * @param request the specified request
     * @param response the specified response
     * @param id the specified article id
     * @throws Exception exception
     */
    @RequestProcessing(value = "/article/{id}", method = HTTPRequestMethod.PUT)
    @Before(adviceClass = {StopwatchStartAdvice.class, LoginCheck.class, CSRFCheck.class, ArticleUpdateValidation.class})
    @After(adviceClass = StopwatchEndAdvice.class)
    public void updateArticle(final HTTPRequestContext context, final HttpServletRequest request, final HttpServletResponse response,
            final String id) throws Exception {
        if (Strings.isEmptyOrNull(id)) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);

            return;
        }

        final JSONObject oldArticle = articleQueryService.getArticleById(id);
        if (null == oldArticle) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND);

            return;
        }

        context.renderJSON();

        final JSONObject requestJSONObject = (JSONObject) request.getAttribute(Keys.REQUEST);

        final String articleTitle = requestJSONObject.optString(Article.ARTICLE_TITLE);
        String articleTags = requestJSONObject.optString(Article.ARTICLE_TAGS);
        final String articleContent = requestJSONObject.optString(Article.ARTICLE_CONTENT);
        //final boolean articleCommentable = requestJSONObject.optBoolean(Article.ARTICLE_COMMENTABLE);
        final boolean articleCommentable = true;
        final int articleType = requestJSONObject.optInt(Article.ARTICLE_TYPE, Article.ARTICLE_TYPE_C_NORMAL);
        final String articleRewardContent = requestJSONObject.optString(Article.ARTICLE_REWARD_CONTENT);
        final int articleRewardPoint = requestJSONObject.optInt(Article.ARTICLE_REWARD_POINT);
        final String ip = Requests.getRemoteAddr(request);

        final JSONObject article = new JSONObject();
        article.put(Keys.OBJECT_ID, id);
        article.put(Article.ARTICLE_TITLE, articleTitle);
        article.put(Article.ARTICLE_CONTENT, articleContent);
        article.put(Article.ARTICLE_EDITOR_TYPE, 0);
        article.put(Article.ARTICLE_COMMENTABLE, articleCommentable);
        article.put(Article.ARTICLE_TYPE, articleType);
        article.put(Article.ARTICLE_REWARD_CONTENT, articleRewardContent);
        article.put(Article.ARTICLE_REWARD_POINT, articleRewardPoint);
        article.put(Article.ARTICLE_IP, "");
        if (StringUtils.isNotBlank(ip)) {
            article.put(Article.ARTICLE_IP, ip);
        }

        final JSONObject currentUser = (JSONObject) request.getAttribute(User.USER);
        if (null == currentUser
                || !currentUser.optString(Keys.OBJECT_ID).equals(oldArticle.optString(Article.ARTICLE_AUTHOR_ID))) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);

            return;
        }

        article.put(Article.ARTICLE_AUTHOR_ID, currentUser.optString(Keys.OBJECT_ID));

        final String authorEmail = currentUser.optString(User.USER_EMAIL);
        article.put(Article.ARTICLE_AUTHOR_EMAIL, authorEmail);

        if (!Role.ADMIN_ROLE.equals(currentUser.optString(User.USER_ROLE))) {
            articleTags = articleMgmtService.filterReservedTags(articleTags);
        }

        try {
            if (Strings.isEmptyOrNull(articleTags)) {
                throw new ServiceException(langPropsService.get("articleTagReservedLabel"));
            }

            article.put(Article.ARTICLE_TAGS, articleTags);

            articleMgmtService.updateArticle(article);
            context.renderTrueResult();
        } catch (final ServiceException e) {
            final String msg = e.getMessage();
            LOGGER.log(Level.ERROR, "Adds article[title=" + articleTitle + "] failed: {0}", e.getMessage());

            context.renderMsg(msg);
        }
    }

    /**
     * Markdowns.
     *
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "html": ""
     * }
     * </pre>
     * </p>
     *
     * @param request the specified http servlet request
     * @param response the specified http servlet response
     * @param context the specified http request context
     * @throws Exception exception
     */
    @RequestProcessing(value = "/markdown", method = HTTPRequestMethod.POST)
    @Before(adviceClass = StopwatchStartAdvice.class)
    @After(adviceClass = StopwatchEndAdvice.class)
    public void markdown2HTML(final HttpServletRequest request, final HttpServletResponse response, final HTTPRequestContext context)
            throws Exception {
        context.renderJSON(true);

        String markdownText = request.getParameter("markdownText");
        if (Strings.isEmptyOrNull(markdownText)) {
            context.renderJSONValue("html", "");

            return;
        }

        final JSONObject currentUser = userQueryService.getCurrentUser(request);
        if (null == currentUser) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);

            return;
        }

        final Set<String> userNames = userQueryService.getUserNames(markdownText);
        for (final String userName : userNames) {
            markdownText = markdownText.replace('@' + userName, "@<a href='" + Latkes.getServePath()
                    + "/member/" + userName + "'>" + userName + "</a>");
        }
        markdownText = shortLinkQueryService.linkArticle(markdownText);
        markdownText = shortLinkQueryService.linkTag(markdownText);
        markdownText = Emotions.convert(markdownText);
        markdownText = Markdowns.toHTML(markdownText);
        markdownText = Markdowns.clean(markdownText, "");

        context.renderJSONValue("html", markdownText);
    }

    /**
     * Gets article preview content.
     *
     * <p>
     * Renders the response with a json object, for example,
     * <pre>
     * {
     *     "html": ""
     * }
     * </pre>
     * </p>
     *
     * @param request the specified http servlet request
     * @param response the specified http servlet response
     * @param context the specified http request context
     * @param articleId the specified article id
     * @throws Exception exception
     */
    @RequestProcessing(value = "/article/{articleId}/preview", method = HTTPRequestMethod.GET)
    @Before(adviceClass = StopwatchStartAdvice.class)
    @After(adviceClass = StopwatchEndAdvice.class)
    public void getArticlePreviewContent(final HttpServletRequest request, final HttpServletResponse response,
            final HTTPRequestContext context, final String articleId) throws Exception {
        context.renderJSON(true).renderJSONValue("html", "");

        final JSONObject article = articleQueryService.getArticle(articleId);
        if (null == article) {
            context.renderFalseResult();

            return;
        }

        final int length = Integer.valueOf("150");
        String content = article.optString(Article.ARTICLE_CONTENT);
        final String authorId = article.optString(Article.ARTICLE_AUTHOR_ID);
        final JSONObject author = userQueryService.getUser(authorId);

        if (null != author && UserExt.USER_STATUS_C_INVALID == author.optInt(UserExt.USER_STATUS)
                || Article.ARTICLE_STATUS_C_INVALID == article.optInt(Article.ARTICLE_STATUS)) {
            context.renderJSONValue("html", langPropsService.get("articleContentBlockLabel"));

            return;
        }

        final Set<String> userNames = userQueryService.getUserNames(content);
        final JSONObject currentUser = userQueryService.getCurrentUser(request);
        final String currentUserName = null == currentUser ? "" : currentUser.optString(User.USER_NAME);
        final String authorName = author.optString(User.USER_NAME);
        if (Article.ARTICLE_TYPE_C_DISCUSSION == article.optInt(Article.ARTICLE_TYPE)
                && !authorName.equals(currentUserName)) {
            boolean invited = false;
            for (final String userName : userNames) {
                if (userName.equals(currentUserName)) {
                    invited = true;

                    break;
                }
            }

            if (!invited) {
                String blockContent = langPropsService.get("articleDiscussionLabel");
                blockContent = blockContent.replace("{user}", "<a href='" + Latkes.getServePath()
                        + "/member/" + authorName + "'>" + authorName + "</a>");

                context.renderJSONValue("html", blockContent);

                return;
            }
        }

        content = Emotions.convert(content);
        content = Markdowns.toHTML(content);

        content = Jsoup.clean(content, Whitelist.none());
        if (content.length() >= length) {
            content = StringUtils.substring(content, 0, length)
                    + " ....";
        }

        context.renderJSONValue("html", content);
    }

    /**
     * Article rewards.
     *
     * @param request the specified http servlet request
     * @param response the specified http servlet response
     * @param context the specified http request context
     * @throws Exception exception
     */
    @RequestProcessing(value = "/article/reward", method = HTTPRequestMethod.POST)
    @Before(adviceClass = StopwatchStartAdvice.class)
    @After(adviceClass = StopwatchEndAdvice.class)
    public void reward(final HttpServletRequest request, final HttpServletResponse response, final HTTPRequestContext context)
            throws Exception {
        final JSONObject currentUser = userQueryService.getCurrentUser(request);
        if (null == currentUser) {
            response.sendError(HttpServletResponse.SC_FORBIDDEN);

            return;
        }

        final String articleId = request.getParameter(Article.ARTICLE_T_ID);
        if (Strings.isEmptyOrNull(articleId)) {
            response.sendError(HttpServletResponse.SC_BAD_REQUEST);

            return;
        }

        context.renderJSON();

        try {
            articleMgmtService.reward(articleId, currentUser.optString(Keys.OBJECT_ID));
        } catch (final ServiceException e) {
            context.renderMsg(langPropsService.get("transferFailLabel"));

            return;
        }

        final JSONObject article = articleQueryService.getArticle(articleId);
        if (null == article) {
            return;
        }

        articleQueryService.processArticleContent(article, request);

        context.renderTrueResult().
                renderJSONValue(Article.ARTICLE_REWARD_CONTENT, article.optString(Article.ARTICLE_REWARD_CONTENT));
    }
}
