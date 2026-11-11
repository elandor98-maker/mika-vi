---
name: skill-authoring
display: Viết kỹ năng
description: Dùng khi người dùng muốn tạo mới, nhập hoặc sửa một kỹ năng (cách làm cố định cho một loại việc): viết thế nào, kiểm tra trùng thế nào, lưu ra sao
aliases: ["创建技能", "新建技能", "写个技能", "保存技能", "导入技能", "改技能", "skill"]
terms: ["技能", "做法", "流程", "步骤", "模板", "以后都这样做", "存成技能", "导入", "改写"]
---
## 什么时候写

- 用户明确要「建一个技能」「把这个做法存下来」「把这段导入成技能」「改一下某个技能」时写。只是聊做法、问能不能做,先回答,不要自己存。
- 技能写的是一类事以后怎么做,不是一次性的事实或偏好。「我不吃香菜」这类是记忆,不是技能。
- 能写下技能的只有 `skill_save`。手边没有这个工具时,直接告诉用户这次存不了,不要说已经存好。

## 先查有没有

1. 用 `skill_search` 按这类事的说法查一次。
2. 有一份个人技能(personal)就是这类事:用 `skill_read` 读全文,在它的基础上改,存时沿用它的名字、带上读到的 version。不要为同一类事再起一份。
3. 只有内置技能对得上:内置的改不了。用户要的是不同做法时,另起一个名字写成自己的技能,并告诉用户以后会按哪份来。

## 先说清再写

用户只给了一句模糊的话(「帮我建个点咖啡的技能」)时,先用 `ask_user` 问清最影响做法的一两点:在哪个 App 或网站、做到哪一步停、有没有固定要求。把要存的要点用几行列给用户确认,再存。用户已经把做法说清楚、或贴来了完整内容,就不用再问。

## 怎么写

整份是一个文档:头部加五个小节,标题照写,全文不超过 4000 字。

```
---
name: order-coffee
display: Đặt cà phê
description: 用户要点一杯咖啡外送或自取时用
aliases: ["点咖啡", "买杯咖啡"]
---
## Lối vào
## Các bước
## Chỗ dễ sai
## Cách nhận biết đã xong
## Khi nào dừng lại và giao cho người dùng
```

- name dùng chữ thường tiếng Anh, số và dấu gạch nối, nói rõ là loại việc gì; display là tên ngắn bằng tiếng Việt.
- description viết một câu «khi nào thì dùng», đưa các cách nói có thể có của người dùng vào aliases: sau này kỹ năng có được dùng tới hay không là nhờ hai chỗ này.
- Lối vào viết bắt đầu từ đâu (App nào, trang web nào hoặc tìm kiếm); Các bước viết câu ngắn có đánh số, mỗi bước một hành động, ghi tên nút và tên trang, không ghi tọa độ.
- Những thứ mỗi lần một khác (tên người, số tiền, địa chỉ, đoạn chữ cần gửi) viết thành `<tham số>` hoặc «người liên hệ do người dùng chỉ định», không ghi chết.
- Chỗ dễ sai viết những điểm dễ đi lệch và cách né; Cách nhận biết đã xong viết làm xong thì nhìn thấy kết quả ở đâu; Khi nào dừng lại viết những lúc cần người dùng đăng nhập, thanh toán, xác nhận hoặc lựa chọn.
- Dấu backtick chỉ dùng để bọc tên công cụ; tên App, chữ trên nút thì không dùng backtick. Không viết mật khẩu, mã xác minh, số thẻ và thông tin riêng tư của người khác.
- Thà ngắn còn hơn dài: những gì model tự biết rồi thì khỏi viết, chỉ viết cách làm riêng của loại việc này và những chỗ dễ vấp.

## 导入用户贴来的内容

用户贴来一段别处写的技能、笔记或流程时,把它改写成上面的格式:保留做法本身,去掉和这里无关的东西(别的软件的命令、手机上用不了的工具、脚本),内容有缺的小节按原意补一两句。原文里的账号、密码这类东西不要带进来。

## 存下和告诉用户

1. 用 `skill_save` 存:markdown 是整份文档,quote 抄用户这次要你建或改技能的原话。
2. 结果 ok 才算存下。照结果告诉用户:技能叫什么、是新建还是改了已有的,在«Cài đặt › Ký ức & dữ liệu»里能看到,下次遇到这类事会先试用它。
3. 结果失败时照原因改了再存一次;还是不行就如实说没存上、为什么,不要说已经建好。
4. 改名、删除这里做不了:请用户在«Cài đặt › Ký ức & dữ liệu»里删掉旧的;要换名字就按新名字另存一份,再请用户删掉旧的。

## Cách nhận biết đã xong

`skill_save` 返回 ok,结果里的名字就是要存的那份;回答里说的名字、新建还是修改,和结果一致。
