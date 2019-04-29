package com.tellyes.platform.attachment.service.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URL;
import java.net.URLEncoder;
import java.rmi.ServerException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Calendar;
import java.util.Date;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import javax.annotation.Resource;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.io.FilenameUtils;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import com.tellyes.common.constants.EncodingConst;
import com.tellyes.common.utils.DateUtil;
import com.tellyes.common.utils.StringUtil;
import com.tellyes.core.constants.Constants;
import com.tellyes.core.exception.ServiceException;
import com.tellyes.core.plugin.search.SearchOperator;
import com.tellyes.core.plugin.search.Searchable;
import com.tellyes.core.plugin.search.filter.SearchFilterHelper;
import com.tellyes.core.plugin.sequence.SnowFlakeSequence;
import com.tellyes.core.utils.WebContextUtil;
import com.tellyes.platform.attachment.constants.AttachmentConst;
import com.tellyes.platform.attachment.dao.IAttachmentDao;
import com.tellyes.platform.attachment.dao.IAttachmentUnitDao;
import com.tellyes.platform.attachment.entity.Attachment;
import com.tellyes.platform.attachment.entity.AttachmentUnit;
import com.tellyes.platform.attachment.service.IAttachmentService;
import com.tellyes.platform.attachment.utils.OfficeConvertUtil;
import com.tellyes.platform.base.common.service.PropertyUtil;

@Service
public class AttachmentServiceImpl implements IAttachmentService {
    // 支持预览的文件后缀
    String[] allowedExtension = {"doc", "docx", "xls", "xlsx", "ppt", "pptx", "bmp", "gif", "jpg", "jpeg", "png",
            "html", "htm", "txt", "pdf"};
    @Resource
    private IAttachmentDao attachmentDao;

    @Resource
    private IAttachmentUnitDao attachmentUnitDao;

    @Override
    public List<Attachment> getAllByUnit(Long unitId) {
        Searchable search = Searchable.newSearchable();
        search.addSearchFilter(SearchFilterHelper.newCondition("unit_id", SearchOperator.eq, unitId));
        search.addSearchFilter(SearchFilterHelper.newCondition("is_deleted", SearchOperator.eq, Boolean.FALSE));
        return attachmentDao.list(search);
    }

    @Override
    public Attachment upload(MultipartFile file, Long unitId) throws IOException {
        return this.upload(file, unitId, null, null);
    }

    @Override
    public Attachment upload(MultipartFile file, Long unitId, String typeCode, String diyPath) throws IOException {
        AttachmentUnit unit = null;
        if (unitId != null) {
            unit = attachmentUnitDao.get(unitId);
        }
        if (unit == null) {
            unit = new AttachmentUnit();
            attachmentUnitDao.create(unit);
        }
        // 附件对象
        Attachment attachment = new Attachment();
        // 构建数据对象
        attachment.setUnitId(unit.getId());
        attachment.setTypeCode(typeCode);
        attachment.setFileName(file.getOriginalFilename());
        // 处理上传位置
        String rootDir = PropertyUtil.getPriorityValue(AttachmentConst.FILE_UPLOAD_PATH, "/upload");
        String filePath;
        if (StringUtil.isBlank(diyPath)) {
            filePath = "default";
        } else {
            filePath = diyPath;
        }
        // 分类目录+时间的年月(yyyyMM)
        filePath = this.joinPath(filePath, DateUtil.getDateMonthFormat(Calendar.getInstance()));
        // +唯一组件
        filePath = this.joinPath(filePath, SnowFlakeSequence.next().toString());
        // +文件后缀
        filePath = filePath + "." + FilenameUtils.getExtension(file.getOriginalFilename());
        attachment.setFilePath(filePath);
        // 上传的目的地
        File desc = this.getAbsoluteFile(this.joinPath(rootDir, filePath));
        // 保存文件
        file.transferTo(desc);
        // 保存数据
        attachmentDao.create(attachment);
        return attachment;
    }

    @Override
    public void preview(Long attachmentId) throws IOException {
        Attachment attachment = this.attachmentDao.get(attachmentId);
        if (attachment != null) {
            String extension = FilenameUtils.getExtension(attachment.getFileName()).toLowerCase();
            if (!isAllowedExtension(extension, allowedExtension)) {
                throw new ServiceException("只支持以下文件的预览：" + StringUtil.join(allowedExtension, "、"));
            }
            String rootDir = PropertyUtil.getPriorityValue(AttachmentConst.FILE_UPLOAD_PATH, "/upload");
            String filePath = this.joinPath(rootDir, attachment.getFilePath());
            // 使用流输出的文件
            String targetPath = filePath;
            // 是否经过转换，处理是否需要删除文件
            Boolean isConvert = Boolean.FALSE;
            String viewExtension = extension;
            if ("xls".equals(extension) || "xlsx".equals(extension)) {
                targetPath = OfficeConvertUtil.convert(filePath, OfficeConvertUtil.HTML);
                isConvert = Boolean.TRUE;
                viewExtension = "html";
            } else if ("doc".equals(extension) || "docx".equals(extension) || "ppt".equals(extension)
                    || "pptx".equals(extension)) {
                targetPath = OfficeConvertUtil.convert(filePath, OfficeConvertUtil.PDF);
                isConvert = Boolean.TRUE;
                viewExtension = "pdf";
            }
            HttpServletResponse response = WebContextUtil.getResponse();
            OutputStream out = response.getOutputStream();
            // 非常重要
            response.reset();
            if ("html".equals(viewExtension) || "htm".equals(viewExtension) || "txt".equals(viewExtension)) {
                // html和txt的输出
                response.setContentType("text/html;charset=utf-8");
            } else if ("pdf".equals(viewExtension)) {
                // pdf的输出
                response.setContentType("application/pdf;charset=utf-8");
            } else {
                // 图片的输出
                response.setContentType("image/jpeg");
            }
            if ("txt".equals(viewExtension)) {
                response.setHeader("Content-type", "text/html;charset=gbk;filename=" + java.net.URLEncoder.encode(attachment.getFileName(), "UTF-8"));
            } else {
                response.setHeader("Content-Disposition",
                        "filename=" + java.net.URLEncoder.encode(attachment.getFileName(), "UTF-8"));
            }
            File targetFile = new File(targetPath);
            BufferedInputStream br = new BufferedInputStream(new FileInputStream(targetFile));
            byte[] buf = new byte[1024];
            int len;
            while ((len = br.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
            br.close();
            out.close();
            if (isConvert) {
                if (targetFile.exists()) {
                    targetFile.delete();
                }
            }
        }
    }

    /**
     * 转换html文件为字符串返回
     *
     * @param filePath 文件路径
     * @param response 返回对象
     * @param isOnLine 是否在线预览
     * @return 转换的数据
     * @throws Exception 异常
     * @author zq
     */
    public static void htmlToString(String filePath, HttpServletResponse response, Boolean isOnLine)
            throws Exception {
        File f = new File(filePath);
        if (!f.exists()) {
            throw new Exception("文件不存在!");
        }
        BufferedInputStream br = new BufferedInputStream(new FileInputStream(f));
        byte[] buf = new byte[1024];
        int len;
        response.reset(); // 非常重要
        URL u = new URL("file:///" + filePath);
        response.setContentType(u.openConnection().getContentType());
        if (isOnLine) {
            response.setHeader("Content-type", "text/html;charset=utf-8;filename=" + java.net.URLEncoder.encode(f.getName(), "UTF-8"));

        } else {
            response.setHeader("Content-type", "text/html;charset=gbk;filename=" + java.net.URLEncoder.encode(f.getName(), "UTF-8"));

        }
        OutputStream out = response.getOutputStream();
        while ((len = br.read(buf)) > 0) {
            out.write(buf, 0, len);
        }
        br.close();
        out.close();
    }

    @Override
    public void download(Long attachmentId) throws IOException {
        HttpServletRequest request = WebContextUtil.getRequest();
        HttpServletResponse response = WebContextUtil.getResponse();
        OutputStream out = response.getOutputStream();
        BufferedInputStream br = null;
        try {
            Attachment attachment = this.attachmentDao.get(attachmentId);
            String fileName = attachment.getFileName();
            String rootDir = PropertyUtil.getPriorityValue(AttachmentConst.FILE_UPLOAD_PATH, "/upload");
            String filePath = this.joinPath(rootDir, attachment.getFilePath());
            File file = new File(filePath);
            br = new BufferedInputStream(new FileInputStream(file));
            byte[] buf = new byte[1024];
            int len;
            String userAgent = request.getHeader("User-Agent");
            response.reset();
            response.setContentType("multipart/form-data");
            if (userAgent != null && userAgent.indexOf("Firefox") >= 0 || userAgent.indexOf("Chrome") >= 0
                    || userAgent.indexOf("Safari") >= 0) {
                response.setHeader("Content-Disposition",
                        "attachment; filename=" + new String(new String((fileName).getBytes(EncodingConst.UTF_8), EncodingConst.ISO_8859_1)));
            } else {
                fileName = URLEncoder.encode(fileName, "UTF8"); // 其他浏览器
                response.setHeader("Content-Disposition", "attachment; filename=" + fileName);

            }
            while ((len = br.read(buf)) > 0) {
                out.write(buf, 0, len);
            }
        } catch (IOException e) {
            throw e;
        } finally {
            if (br != null) {
                br.close();
            }
            if (out != null) {
                out.close();
            }
        }
    }

    @Override
    public void remove(Long attachmentId) {
        Attachment attachment = this.attachmentDao.get(attachmentId);
        /*
         * String rootDir =
		 * PropertyUtil.getPriorityValue(AttachmentConst.FILE_UPLOAD_PATH,
		 * "/upload"); File file = new File(this.joinPath(rootDir,
		 * attachment.getFilePath())); file.delete();
		 */
        attachment.setDeleted(Boolean.TRUE);
        this.attachmentDao.update(attachment);
    }

    @Override
    public void downloadZipByUnitIds(Long... unitIds) throws IOException {
        if (unitIds != null && unitIds.length != 0) {
            HttpServletResponse response = WebContextUtil.getResponse();
            List<Attachment> attachments = this.attachmentDao.getAll();
            if (attachments == null || attachments.isEmpty()) {
                throw new ServerException("批量下载附件不存在");
            }
            List<Attachment> attachmentList = this.calcAttachementsByUnits(attachments, unitIds);
            if (attachmentList == null || attachmentList.isEmpty()) {
                throw new ServerException("批量下载附件不存在");
            }
            downloadFiles(response, attachmentList);
        }
    }

    @Override
    public void downloadZipByFileIds(Long... fileIds) throws IOException {
        if (fileIds != null && fileIds.length != 0) {
            HttpServletResponse response = WebContextUtil.getResponse();
            List<Attachment> attachments = this.attachmentDao.getAll();
            if (attachments == null || attachments.isEmpty()) {
                throw new ServerException("批量下载附件不存在");
            }
            List<Attachment> attachmentList = this.calcAttachementsByDelete(attachments, fileIds);
            if (attachmentList == null || attachmentList.isEmpty()) {
                throw new ServerException("批量下载附件不存在");
            }
            downloadFiles(response, attachmentList);
        }
    }

    /**
     * 下载附件（单个直接下载，多个打包下载）
     *
     * @param response
     * @param attachmentList
     * @throws IOException
     */
    private void downloadFiles(HttpServletResponse response, List<Attachment> attachmentList) throws IOException {
        if (attachmentList.size() == 1) {
            // 单个直接下载
            this.download(attachmentList.get(0).getId());
        } else {
            List<File> files = new ArrayList<>();
            Set<String> fileSet = new HashSet<>();
            String rootDir = PropertyUtil.getPriorityValue(AttachmentConst.FILE_UPLOAD_PATH, "/upload");
            for (Attachment s : attachmentList) {
                int num = 0;
                files.add(this.reName(s.getFileName(), num, fileSet, rootDir, s.getFilePath()));
            }
            // 创建一个临时压缩文件，把文件流全部注入到这个文件中
            SimpleDateFormat sdf = new SimpleDateFormat("yyyyMMddhhmmss");
            String timeString = sdf.format(new Date());
            String zipFilePath = timeString + ".zip";
            File file = new File(zipFilePath);
            if (!file.exists()) {
                file.createNewFile();
            }
            response.reset();
            // 创建文件输出流
            FileOutputStream fous = new FileOutputStream(file);
            ZipOutputStream zipOut = new ZipOutputStream(fous);
            zipFile(files, zipOut);
            zipOut.close();
            fous.close();
            downloadZip2(file, response);
            // 删除文件
            for (File file1 : files) {
                if (file1 != null) {
                    file1.delete();
                }
            }
        }
    }

    /**
     * 处理文件
     *
     * @param path
     * @return
     * @throws IOException
     */
    private String copyFile(String path, String newPath) throws IOException {
        File file = new File(path);
        if (file.exists()) {
            int index = path.lastIndexOf("/");
            String left = path.substring(0, index);
            String getPath = this.joinPath(left, newPath);
            File newfile = new File(getPath);
            copyFileUsingFileStreams(file, newfile);
            return getPath;
        }
        return null;
    }

    /**
     * 复制文件
     *
     * @param source
     * @param dest
     * @throws IOException
     */
    private static void copyFileUsingFileStreams(File source, File dest) throws IOException {
        InputStream input = null;
        OutputStream output = null;
        try {
            input = new FileInputStream(source);
            output = new FileOutputStream(dest);
            byte[] buf = new byte[1024];
            int bytesRead;
            while ((bytesRead = input.read(buf)) != -1) {
                output.write(buf, 0, bytesRead);
            }
        } finally {
            input.close();
            output.close();
        }
    }

    /**
     * 文件重命名
     *
     * @param
     * @param newName
     * @param num
     * @return
     */
    private File reName(String newName, int num, Set<String> fileSet, String rootDir, String uploadPath)
            throws IOException {
        File file = null;
        if (!fileSet.contains(newName)) {
            // 没有在set里面先加入set再直接使用
            fileSet.add(newName);
            // 复制一份
            String filePath = this.joinPath(rootDir, uploadPath);
            String fileCopy = this.copyFile(filePath, newName);
            if (fileCopy != null) {
                file = new File(fileCopy);
            }
        } else {
            int indexPoint = newName.indexOf(Constants.POINT);
            String left = newName.substring(0, indexPoint);
            String right = newName.substring(indexPoint + 1);
            String leftChar = "(";
            String rightChar = ")";
            // 括号
            if (left.contains(leftChar) && left.contains(rightChar)) {
                // 已经再使用别名了
                int indexAlisaLeft = newName.indexOf(leftChar);
                int indexAlisaRight = newName.indexOf(rightChar);
                String leftAlisa = newName.substring(0, indexAlisaLeft);
                String rightAlisa = newName.substring(indexAlisaRight + 1);
                String getPath = leftAlisa + leftChar + ++num + rightChar + rightAlisa;
                file = this.reName(getPath, num, fileSet, rootDir, uploadPath);
            } else {
                // 没有使用别名
                String getPath = left + leftChar + ++num + rightChar + Constants.POINT + right;
                file = this.reName(getPath, num, fileSet, rootDir, uploadPath);
            }
        }
        return file;
    }

    /**
     * 处理unit下的文件
     *
     * @param attachments
     * @param unitIds
     * @return
     */
    private List<Attachment> calcAttachementsByUnits(List<Attachment> attachments, Long... unitIds) {
        List<Attachment> attachmentList = new ArrayList<>();
        for (Attachment attachment : attachments) {
            for (Long unitId : unitIds) {
                // Id对应的和删除状态为false的数据才能下载
                if (attachment.getUnitId().equals(unitId) && !attachment.getDeleted()) {
                    attachmentList.add(attachment);
                }
            }
        }
        return attachmentList;
    }

    /**
     * 处理不是删除状态的文件
     *
     * @param attachments
     * @param fileIds
     * @return
     */
    private List<Attachment> calcAttachementsByDelete(List<Attachment> attachments, Long... fileIds) {
        List<Attachment> attachmentList = new ArrayList<>();
        for (Attachment attachment : attachments) {
            for (Long unitId : fileIds) {
                // Id对应的和删除状态为false的数据才能下载
                if (attachment.getId().equals(unitId) && !attachment.getDeleted()) {
                    attachmentList.add(attachment);
                }
            }
        }
        return attachmentList;
    }

    /**
     * 判断MIME类型是否是允许的MIME类型
     *
     * @param extension
     * @param allowedExtension
     * @return
     */
    private boolean isAllowedExtension(String extension, String[] allowedExtension) {
        for (String str : allowedExtension) {
            if (str.equalsIgnoreCase(extension)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 连接url
     *
     * @param first
     * @param second
     * @return
     */
    private String joinPath(String first, String second) {
        if (StringUtil.isBlank(first)) {
            return "";
        }
        if (StringUtil.isBlank(second)) {
            return first;
        }
        Boolean end = first.endsWith("/");
        Boolean start = second.startsWith("/");
        if (end && start) {
            // 去掉多余的斜线
            return first + second.substring(1);
        } else if (!end && !start) {
            // 增加斜线
            return first + "/" + second;
        }
        return first + second;
    }

    private File getAbsoluteFile(String filename) throws IOException {
        File desc = new File(filename);
        boolean result = false;
        if (!desc.getParentFile().exists()) {
            result = desc.getParentFile().mkdirs();
        }
        if (!desc.exists() && result) {
            desc.createNewFile();
        }
        return desc;
    }

    /**
     * 把接受的全部文件打成压缩包
     *
     * @param files
     * @param outputStream
     */
    @SuppressWarnings("rawtypes")
    public static void zipFile(List files, ZipOutputStream outputStream) {
        int size = files.size();
        for (int i = 0; i < size; i++) {
            File file = (File) files.get(i);
            zipFile(file, outputStream);
        }
    }

    public static HttpServletResponse downloadZip2(File file, HttpServletResponse response) {
        try {
            // 以流的形式下载文件。
            InputStream fis = new BufferedInputStream(new FileInputStream(file.getPath()));
            byte[] buffer = new byte[fis.available()];
            fis.read(buffer);
            fis.close();
            // 清空response
            response.reset();

            OutputStream toClient = new BufferedOutputStream(response.getOutputStream());
            response.setContentType("application/octet-stream");

            // 如果输出的是中文名的文件，在此处就要用URLEncoder.encode方法进行处理
            response.setHeader("Content-Disposition",
                    "attachment;filename=" + URLEncoder.encode(file.getName(), "UTF-8"));
            toClient.write(buffer);
            toClient.flush();
            toClient.close();
        } catch (IOException ex) {
            ex.printStackTrace();
        } finally {
            try {
                File f = new File(file.getPath());
                f.delete();
            } catch (Exception e) {
                e.printStackTrace();
            }
        }
        return response;
    }

    /**
     * 根据输入的文件与输出流对文件进行打包
     *
     * @param inputFile
     * @param ouputStream
     */
    public static void zipFile(File inputFile, ZipOutputStream ouputStream) {
        try {
            if (inputFile.exists()) {
                /**
                 * 如果是目录的话这里是不采取操作的， 至于目录的打包正在研究中
                 */
                if (inputFile.isFile()) {
                    FileInputStream IN = new FileInputStream(inputFile);
                    BufferedInputStream bins = new BufferedInputStream(IN, 512);
                    // org.apache.tools.zip.ZipEntry
                    ZipEntry entry = new ZipEntry(inputFile.getName());
                    ouputStream.putNextEntry(entry);
                    // 向压缩文件中输出数据
                    int nNumber;
                    byte[] buffer = new byte[512];
                    while ((nNumber = bins.read(buffer)) != -1) {
                        ouputStream.write(buffer, 0, nNumber);
                    }
                    // 关闭创建的流对象
                    bins.close();
                    IN.close();
                } else {
                    try {
                        File[] files = inputFile.listFiles();
                        if (files != null && files.length != 0) {
                            for (int i = 0; i < files.length; i++) {
                                zipFile(files[i], ouputStream);
                            }
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
