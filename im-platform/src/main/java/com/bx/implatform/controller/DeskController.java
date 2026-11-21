package com.bx.implatform.controller;

import com.bx.implatform.result.Result;
import com.bx.implatform.result.ResultUtils;
import com.bx.implatform.service.impl.MineDeskService;
import com.bx.implatform.session.SessionContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.hibernate.validator.constraints.Length;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@Tag(name = "收藏、反馈、会话偏好")
@RestController
@RequiredArgsConstructor
public class DeskController {

    private final MineDeskService desk;

    @GetMapping("/friend/mark/list")
    @Operation(summary = "备注、星标、电话、描述、置顶、免打扰")
    public Result<List<Map<String, Object>>> marks() {
        return ResultUtils.success(desk.userMarks(uid()));
    }

    @GetMapping("/friend/mark/{friendId}")
    @Operation(summary = "一个人的备注")
    public Result<Map<String, Object>> mark(@PathVariable Long friendId) {
        return ResultUtils.success(desk.userMark(uid(), friendId));
    }

    @GetMapping("/group/pref/list")
    @Operation(summary = "群置顶、免打扰、提示音")
    public Result<List<Map<String, Object>>> groupPrefs() {
        return ResultUtils.success(desk.groupPrefs(uid()));
    }

    @GetMapping("/group/audioTip")
    @Operation(summary = "某个群的提示音，没设置过是开")
    public Result<Map<String, Object>> audioTip(@RequestParam Long groupId) {
        return ResultUtils.success(desk.groupPref(uid(), groupId));
    }

    @PutMapping("/group/audioTip")
    @Operation(summary = "单独开关某个群的提示音")
    public Result<Map<String, Object>> setAudioTip(@RequestParam Long groupId, @RequestParam Boolean enabled) {
        long userId = uid();
        desk.saveGroupAudio(userId, groupId, enabled);
        return ResultUtils.success(desk.groupPref(userId, groupId));
    }

    @PostMapping("/group/transfer")
    @Operation(summary = "群主转让，原群主留在群里")
    public Result<Void> transfer(@Valid @RequestBody TransferForm form) {
        desk.transfer(uid(), form.getGroupId(), form.getUserId());
        return ResultUtils.success();
    }

    @PostMapping("/favorite")
    @Operation(summary = "收藏文字、图片、视频、文件或链接")
    public Result<Map<String, Object>> addFavorite(@Valid @RequestBody FavoriteForm form) {
        return ResultUtils.success(desk.addFavorite(uid(), form.getKind(), form.getContent(), form.getTitle(),
            form.getUrl(), form.getSourceId(), form.getTags()));
    }

    @PutMapping("/favorite/{id}/tags")
    @Operation(summary = "改一条收藏的标签")
    public Result<Map<String, Object>> updateFavoriteTags(@PathVariable Long id, @RequestBody FavoriteTagForm form) {
        return ResultUtils.success(desk.updateFavoriteTags(uid(), id, form == null ? null : form.getTags()));
    }

    @GetMapping("/favorite/tags")
    @Operation(summary = "当前账号的全部收藏标签")
    public Result<List<String>> favoriteTags() {
        return ResultUtils.success(desk.listTags(uid()));
    }

    @PostMapping("/favorite/tag")
    @Operation(summary = "新建一个收藏标签，先不挂到某条收藏上")
    public Result<List<String>> addFavoriteTag(@RequestBody FavoriteTagForm form) {
        return ResultUtils.success(desk.addTag(uid(), form == null ? null : form.getName()));
    }

    @GetMapping("/favorite/list")
    @Operation(summary = "收藏列表，新的在前")
    public Result<List<Map<String, Object>>> favorites() {
        return ResultUtils.success(desk.listFavorites(uid()));
    }

    @DeleteMapping("/favorite/{id}")
    @Operation(summary = "取消收藏")
    public Result<Void> removeFavorite(@PathVariable Long id) {
        desk.removeFavorite(uid(), id);
        return ResultUtils.success();
    }

    @PostMapping("/feedback")
    @Operation(summary = "意见反馈")
    public Result<Map<String, Object>> feedback(@Valid @RequestBody FeedbackForm form) {
        return ResultUtils.success(desk.addFeedback(uid(), form.getContent(), form.getContact(), form.getImages()));
    }

    private static long uid() {
        return SessionContext.getSession().getUserId();
    }

    @Data
    public static class FavoriteForm {
        @NotBlank(message = "收藏类型为空")
        private String kind;
        @Length(max = 2000, message = "收藏内容过长")
        private String content;
        @Length(max = 128, message = "标题过长")
        private String title;
        @Length(max = 512, message = "地址过长")
        private String url;
        @Length(max = 64, message = "来源过长")
        private String sourceId;
        private List<String> tags;
    }

    @Data
    public static class FavoriteTagForm {
        private List<String> tags;
        @Length(max = 16, message = "标签不能大于16字符")
        private String name;
    }

    @Data
    public static class FeedbackForm {
        @NotBlank(message = "反馈内容为空")
        @Length(max = 1000, message = "反馈内容过长")
        private String content;
        @Length(max = 64, message = "联系方式过长")
        private String contact;
        private List<String> images;
    }

    @Data
    public static class TransferForm {
        @NotNull(message = "群id为空")
        private Long groupId;
        @NotNull(message = "用户id为空")
        private Long userId;
    }
}
