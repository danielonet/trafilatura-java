// Java port of trafilatura/xpaths.py
// The expressions are generated from the Python module (f-strings and token groups expanded).
package trafilatura.trafilatura;

import java.util.List;

import trafilatura.lxml.XPath;

/**
 * X-Path expressions used to extract or filter the main text content,
 * and to extract metadata.
 */
public final class Xpaths {
    private Xpaths() {
    }

    /** Join concept tokens into a regex alternation; reject an empty group (would match every element). */
    public static String _alt(String... tokens) {
        if (tokens.length == 0) { // not an assert: must hold with assertions disabled
            throw new IllegalArgumentException("empty token group would make re:test(...) match everything");
        }
        return String.join("|", tokens);
    }

    public static final String REGEXP_NS = "http://exslt.org/regular-expressions";

    public static final List<XPath> BODY_XPATH = List.of(
            new XPath(".//*[self::article or self::div or self::main or self::section][ @class='post' or @class='entry' or @itemprop='articleBody' or @id='articleContent' or re:test(@id, '(?:entry|article|art)-content|article__content|article(?:-|__)?body|articleBody|body-text') or re:test(@class, 'post[-_]text|post-body|post-?entry|post[-_]?content|postContent|post_inner_wrapper|article-?text|articleText|(?:entry|page|text|article|art)-content|article__content|article(?:-|__)?body|articleBody|ArticleContent|body-text|article__container') ][1]"),
            new XPath("(.//article)[1]"),
            new XPath("(.//*[self::article or self::div or self::main or self::section][ @role='article' or @id='article' or @id='story' or @class='postarea' or @class='art-postcontent' or @class='text' or @class='cell' or @class='story' or re:test(@id, '^primary|story-body') or re:test(@class, 'fulltext', 'i') or re:test(@class, '^article |post-bodycopy|story-?content|(?:theme|blog|section|single)-content|single-post|main-column|wpb_text_column|story-body|field-body') ])[1]"),
            new XPath("(.//*[self::article or self::div or self::main or self::section][ @id='content' or @class='content' or re:test(@id, 'content-main|content-body|contentBody') or re:test(@class, 'content[-_]main|content(?:-|__)body') or contains(translate(@id, 'CM','cm'), 'main-content') or contains(translate(@class, 'CM','cm'), 'main-content') or contains(translate(@class, 'CP','cp'), 'page-content') ])[1]"),
            new XPath("(.//*[self::article or self::div or self::section][ starts-with(@class, 'main') or starts-with(@id, 'main') or starts-with(@role, 'main')])[1]|(.//main)[1]"));

    public static final List<XPath> COMMENTS_XPATH = List.of(
            new XPath(".//*[self::div or self::list or self::section][ re:test(@id|@class, 'comment-?list') or re:test(@class, 'comment-page|comments-content|post-comments')]"),
            new XPath(".//*[self::div or self::section or self::list][ re:test(@id|@class, '^comment[s-]') or re:test(@class, '^Comments|article-comments')]"),
            new XPath(".//*[self::div or self::section or self::list][ re:test(@id, '^(?:comol|disqus_thread|dsq-comments)')]"),
            new XPath(".//*[self::div or self::section][ starts-with(@id, 'social') or contains(@class, 'comment')]"));

    public static final List<XPath> REMOVE_COMMENTS_XPATH = List.of(
            new XPath(".//*[self::div or self::list or self::section or self::details][ re:test(@id, '^(?:[Cc]omment(?!ary)|comol|disqus_thread|dsq-comments)') or re:test(@class, '^[Cc]omment(?!ary)|(?:article|post)-comments')]"));

    public static final List<XPath> REMOVE_COMMENTS_AND_LISTS_XPATH = List.of(
            new XPath(".//*[self::div or self::list or self::section or self::details or self::ol or self::ul][ re:test(@id, '^(?:[Cc]omment(?!ary)|comol|disqus_thread|dsq-comments)') or re:test(@class, '^[Cc]omment(?!ary)|(?:article|post)-comments')][ not(self::ol or self::ul) or re:test(@id, '^(?:[Cc]omment(?!ary)|comol)') or re:test(@class, '^[Cc]omment(?!ary)|(?:article|post)-comments')]"));

    public static final List<XPath> RAW_TREE_PRUNE_XPATH = List.of(
            new XPath(".//*[self::div or self::section or self::aside][ re:test(@id, 'mvp-post-add-(?:box|wrap)|infinite-?scroll') or re:test(@class, 'mvp-post-add-(?:box|wrap)|infinite-?scroll')][ not(.//h1) or re:test(@id, 'mvp-post-add-(?:box|wrap)') or re:test(@class, 'mvp-post-add-(?:box|wrap)')]"),
            new XPath(".//*[self::div or self::section or self::aside][contains(@class, 'elementor-share-buttons')]"));

    public static final List<XPath> OVERALL_DISCARD_XPATH = List.of(
            new XPath(".//*[self::div or self::item or self::list or self::p or self::section or self::span][ @data-lp-replacement-content or contains(translate(@role, 'N', 'n'), 'nav') or contains(@data-component, 'MostPopularStories') or re:test(@id|@class, 'cookie') or re:test(@id, '^shar|social|viral|newsletter|syndication|tags|sidebar|banner|bread-?crumb|button|author|^(?:jp-|dpsp-content)|bmdh|footer|Footer|share|Share|nav|Nav|menu|related|message-container|premium|^ad-') or re:test(@class, '^shar|social|viral|newsletter|syndication|tags|sidebar|banner|bread-?crumb|button|author|^(?:nav|post-nav|ZendeskForm)|subnav|avigation|navbar|navbox|menu|bar| ad |-ad-|(?:^| )ad-|dfp[Aa]d|outbrain|taboola|criteo|paid-?content|widget|footer|Footer|byline|Byline|share-|sociable|embedded|embed|tag-list|consent|modal-content|permission|elated|next-|-stories|most-popular|more-on|meta|rating|attachment|timestamp|user-info|user-profile|-icon|article-infos|message-container|slide|viewport|overlay|options|expand|obfuscated|blurred|mol-factbox|yin|zlylin|nfoline')]"),
            new XPath(".//*[@class='comments-title' or starts-with(@id|@class, 'reply-') or re:test(@id|@style, 'hidden') or contains(@style, 'display:none') or contains(@style, 'display: none') or re:test(@id, 'reader-comments|akismet') or re:test(@class, '^hide-|comments-title|nocomments|-reply-|message|akismet|suggest-links|-hide-|hide-print| hidden| hide|noprint|notloaded') or @aria-hidden='true']"));

    public static final List<XPath> TEASER_DISCARD_XPATH = List.of(
            new XPath(".//*[self::div or self::item or self::list or self::p or self::section or self::span][ contains(translate(@id, 'T', 't'), 'teaser') or contains(translate(@class, 'T', 't'), 'teaser')]"));

    public static final List<XPath> PRECISION_DISCARD_XPATH = List.of(
            new XPath(".//header"),
            new XPath(".//*[self::div or self::item or self::list or self::p or self::section or self::span][ re:test(@id|@class, '(^|\\s)bottom|bottom(\\s|$)') or re:test(@id|@class, '(^|\\s)link(\\s|$)') or contains(@style, 'border')]"));

    public static final List<XPath> DISCARD_IMAGE_ELEMENTS = List.of(
            new XPath(".//*[self::div or self::item or self::list or self::p or self::section or self::span][ contains(@id, 'caption') or contains(@class, 'caption')]"));

    public static final List<XPath> COMMENTS_DISCARD_XPATH = List.of(
            new XPath(".//*[self::div or self::section][starts-with(@id, 'respond')]"),
            new XPath(".//cite|.//quote"),
            new XPath(".//*[ @class='comments-title' or contains(@style, 'display:none') or re:test(@class, 'comments-title|nocomments|-reply-|message|signin') or re:test(@id|@class, '^reply-|akismet')]"));

    public static final List<XPath> AUTHOR_XPATHS = List.of(
            new XPath("//*[self::a or self::address or self::div or self::link or self::p or self::span or self::strong][ @rel='author' or @id='author' or @class='author' or @itemprop='author name' or rel='me' or @data-testid='AuthorCard' or @data-testid='AuthorURL' or re:test(@class, 'author-?name|AuthorName|authorName')]|//author"),
            new XPath("//*[self::a or self::div or self::h3 or self::h4 or self::p or self::span][ @class='byline' or @class='username' or @class='byl' or @class='BBL' or contains(@itemprop, 'author') or re:test(@id, 'author|zuozhe|bianji|xiaobian') or re:test(@class, 'author|channel-name|zuozhe|bianji|xiaobian|submitted-by|posted-by|journalist-name')]"),
            new XPath("//*[ contains(@data-component, 'Byline') or contains(@itemprop, 'author') or re:test(@id, '[Aa]uthor') or re:test(@class, '[Aa]uthor|screenname|writer|[Bb]yline')]"));

    public static final List<XPath> AUTHOR_DISCARD_XPATHS = List.of(
            new XPath(".//*[self::a or self::div or self::section or self::span][ @id='comments' or @class='comments' or @class='title' or @class='date' or re:test(@id, '^comments|comment-?list|ProductReviews') or re:test(@class, '^[Cc]omments|commentlist|comments-list|sidebar|is-hidden|quote|embedly-instagram|article-(?:share|support)|print|category|meta-date|meta-reviewer') or contains(@data-component, 'Figure')]"),
            new XPath("//time|//figure"));

    public static final List<XPath> CATEGORIES_XPATHS = List.of(
            new XPath("//div[ re:test(@class, '^(?:post-?info|post-?meta|meta|entry-meta|entry-info|entry-utility)') or starts-with(@id, 'postpath')]//a[@href]"),
            new XPath("//p[starts-with(@class, 'postmeta') or starts-with(@class, 'entry-categories') or @class='postinfo' or @id='filedunder']//a[@href]"),
            new XPath("//footer[starts-with(@class, 'entry-meta') or starts-with(@class, 'entry-footer')]//a[@href]"),
            new XPath("//*[self::li or self::span][@class='post-category' or @class='postcategory' or @class='entry-category' or contains(@class, 'cat-links')]//a[@href]"),
            new XPath("//header[@class='entry-header']//a[@href]"),
            new XPath("//div[@class='row' or @class='tags']//a[@href]"));

    public static final List<XPath> TAGS_XPATHS = List.of(
            new XPath("//div[@class='tags']//a[@href]"),
            new XPath("//p[starts-with(@class, 'entry-tags')]//a[@href]"),
            new XPath("//div[@class='row' or @class='jp-relatedposts' or @class='entry-utility' or re:test(@class, '^(?:tag|postmeta|meta)')]//a[@href]"),
            new XPath("//*[@class='entry-meta' or contains(@class, 'topics') or contains(@class, 'tags-links')]//a[@href]"));

    public static final List<XPath> TITLE_XPATHS = List.of(
            new XPath("//*[self::h1 or self::h2][ re:test(@class, '(?:post-|entry-|article-|post__)title|headline') or contains(@id, 'headline') or contains(@itemprop, 'headline')]"),
            new XPath("//*[@class='entry-title' or @class='post-title']"),
            new XPath("//*[self::h1 or self::h2 or self::h3][ contains(@class, 'title') or contains(@id, 'title')]"));

}
