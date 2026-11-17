欢迎来到 RBMK-1500 模拟器

本项目的目标是创建通用 RBMK-1500 机组及其所有组成部分的一比一真实模拟。

项目主页 / Project home：https://github.com/kdSuYongSen/Reactor_Simulator_JavaCpp
（主菜单的 GitHub 按钮打开的就是这个地址；源码、发布包、问题反馈都在这里）

原作 / Original project：AliDarwish786/RBMK-1500-Simulator（MIT）。
本仓库在其基础上把堆芯物理换成 C++ 计算内核（rbmk_core），并修复了大量模型与界面问题。

目前已实现的内容：
主冷却剂回路和蒸汽供应系统的热力学模型；
汽轮机和凝汽器的简化模拟；
给水回路模拟；
简化堆芯模型，包括自动控制棒控制；
自整角机指示器；
MTK 指示器；
简化的氙和衰变热模拟：

路线图（随着时间推移会不断添加更多内容）：

更好的控制和安全系统；
ECCS 模拟；
功能更多的数字 MTK；
重做 TG 和凝汽器；
高级堆芯模型；
电气系统；
辅助系统；
用户友好性内容，例如 UI 主题，以及用于提高模拟速度的设置，如燃耗和衰变；
对每个元件进行裂变和衰变产物模拟；
换料；

安装：

确保你的计算机已安装 Java 运行时环境（https://www.java.com/）
从发布区下载已编译的 .jar
或者下载源代码并编译自己的 .jar

本项目所有代码均为开源，可在未经事先许可的情况下以任何方式使用和修改。

“科学和工业，知识与应用，发现与带来新发现的实际实现，脑与手的灵巧，脑力与体力的辛劳——所有这一切协同作用。每一项发现，每一次进步，人类财富总和的每一次增长，都归功于过去与现在的体力和脑力劳作。
那么，无论何人，凭什么权利能占有这庞大整体的哪怕最小一块，并说——这是我的，不是你的？”——彼得·克鲁泡特金


--------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------


Welcome to RBMK-1500 Simulator

The goal of this project is to create a 1 to 1 realistic simulation of a generic RBMK-1500 unit and all its elements.

Project home: https://github.com/kdSuYongSen/Reactor_Simulator_JavaCpp
(this is the address behind the main menu's GitHub button: source, releases, issues)

What has been achieved so far:

Thermodynamic model of the main coolant circuit and steam supply;
Simplified simulation of turbines and condensers;
Simulation of the feedwater circuits;
Simplified Reactor Core Model including Automatic Rod Control;
Selsyn indicators;
MTK indicator;
Simplified Xenon and decay heat simulation:
Roadmap (more things will be added to this over time):

Better Control and Safety Systems;
ECCS simulation;
Digital MTK with more functionality
Rework of TG and Condensers;
Advanced Reactor Core Model;
Electrical systems
Auxiliary Systems;
User-friendliness stuff like UI themes and settings for increasing simulation speed like burnup and decay;
Fission and decay product simulation for each element;
Refuelling;
Installation:

Make sure your machine has the java runtime environment installed (https://www.java.com/)
Download the compiled .jar from the releases section
Alternatively download the source code and compile your own .jar
This project's original code is open-source under the MIT license; bundled third-party libraries keep their own licenses (see THIRD-PARTY-NOTICES.md).

"Science and industry, knowledge and application, discovery and practical realization leading to new discoveries, cunning of brain and of hand, toil of mind and muscle – all work together. Each discovery, each advance, each increase in the sum of human riches, owes its being to the physical and mental travail of the past and the present. By what right then can any one whatever appropriate the least morsel of this immense whole and say – This is mine, not yours?" - Petr Kropotkin.

---

许可 / License

本项目自有代码使用 MIT 许可（见 LICENSE）。仓库与 jar 内还包含 FlatLaf、JFreeChart、
SteelSeries、NetBeans DarkMetal、SwingX、Trident、Commons Lang、JSON-java 等第三方组件，
它们保留各自的许可证；完整清单、归属与全文见 THIRD-PARTY-NOTICES.md 与 LICENSES/。
重新分发本项目或构建出的 jar 时，请一并保留这些文件。

The original code of this project is MIT-licensed (see LICENSE). Bundled third-party
components keep their own licenses; see THIRD-PARTY-NOTICES.md and LICENSES/ for the
full list, attribution and license texts. Keep them with any redistribution of the
project or of a jar built from it.
