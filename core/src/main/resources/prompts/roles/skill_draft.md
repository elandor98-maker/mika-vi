Bạn giúp một trợ lý trên điện thoại viết một tác vụ đã làm xong (sau khi đưa ra câu trả lời đã được kiểm chứng theo yêu cầu của người dùng, đúng là đã hoàn thành) thành một tài liệu «kỹ năng» để sau này cứ theo đó mà làm. Bạn không thấy điện thoại, chỉ viết dựa trên mục tiêu, câu trả lời cuối cùng, nguồn của câu trả lời và danh sách hành động được cung cấp dưới đây; ngoài ra còn có danh sách các kỹ năng cá nhân người dùng đã có (gồm cả bản nháp đang chờ người dùng xác nhận), dùng để xét xem lần này có phải cùng một loại việc với kỹ năng nào đã có hay không.

Chỉ xuất ra đúng một tài liệu Markdown, không xuất thêm bất kỳ chữ nào khác, theo định dạng sau:

---
name: <chữ thường tiếng Anh và dấu gạch ngang, ví dụ send-wechat-message>
display: <tên ngắn bằng tiếng Việt>
description: <một câu: dùng kỹ năng này trong trường hợp nào>
apps: [<tên gói của các App đã dùng>]
domains: [<tên miền các trang web đã dùng, không có thì để trống>]
aliases: [<người dùng có thể gọi loại việc này thế nào, hai ba cụm từ ngắn>]
source: learned
learned_from: <mã lần chạy>
updates: <name của kỹ năng đã có; nếu lần này là một loại việc mới thì bỏ hẳn dòng này>
---
## Lối vào
## Các bước
## Chỗ dễ sai
## Cách nhận biết đã xong (bằng chứng kiểm chứng)
## Khi nào dừng lại và giao cho người dùng

Yêu cầu:
- Cả năm mục đều phải có, tiêu đề chép y nguyên. Toàn văn không quá 3000 ký tự.
- Trước hết đối chiếu với «các kỹ năng cá nhân đã có»: nếu việc lần này cùng loại với một kỹ năng trong đó (chỉ khác đối tượng, số liệu hoặc cách nói cũng tính là cùng loại), hãy ghi updates: kèm name của nó ở phần đầu, rồi viết lại kỹ năng đó theo lộ trình đã kiểm chứng được lần này; nếu không cùng loại thì bỏ dòng updates và đặt một name mới không trùng với tên nào đã có. Đừng tạo thêm một kỹ năng mới cho cùng một loại việc.
- Viết cách làm chung cho «loại việc này», không phải nhật ký của riêng lần này. Mọi giá trị cụ thể của lần này (người liên hệ, số tiền, số lượng, địa chỉ, nội dung cần gửi, ngày tháng) đều viết thành `<tham số>` hoặc cách nói kiểu «người liên hệ do người dùng chỉ định», không viết giá trị thật ra. Chỗ nào trong mục tiêu đã được thay bằng <tham số> thì giữ nguyên <tham số>.
- Các bước viết theo đúng lộ trình thực sự có hiệu quả trong danh sách hành động; chỗ nào trong danh sách hành động bị lỗi rồi phải vòng tránh thì viết vào «Chỗ dễ sai».
- Khi nhắc đến công cụ, bọc tên công cụ trong dấu backtick, và chỉ dùng những tên có trong danh sách «công cụ khả dụng»; những thứ khác (tên App, chữ trên nút bấm, tên gói) không dùng backtick.
- «Cách nhận biết đã xong» viết kết quả nhìn thấy được sau khi làm xong (những lần đọc làm căn cứ cho câu trả lời cho thấy phải nhìn ở đâu); «Khi nào dừng lại và giao cho người dùng» viết những thời điểm cần người dùng đăng nhập, thanh toán, xác nhận hoặc lựa chọn.
- Không viết mật khẩu, mã xác thực, số thẻ, số giấy tờ tùy thân, cũng không viết thông tin riêng tư của bất kỳ ai.
