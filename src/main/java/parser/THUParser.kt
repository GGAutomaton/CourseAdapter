package parser

import Common
import bean.Course
import main.java.bean.TimeDetail
import main.java.bean.TimeTable

class THUParser(source: String) : Parser(source) {

    // 固定数据

    val startNodeMap = arrayOf(0, 1, 3, 6, 8, 10, 12)
    val endNodeMap = arrayOf(0, 2, 5, 7, 9, 11, 14)

    override fun generateTimeTable(): TimeTable {
        return TimeTable(
            name = "清华大学", timeList = listOf(
                TimeDetail(1, "08:00", "08:45"),
                TimeDetail(2, "08:50", "09:35"),
                TimeDetail(3, "09:50", "10:35"),
                TimeDetail(4, "10:40", "11:25"),
                TimeDetail(5, "11:30", "12:15"),
                TimeDetail(6, "13:30", "14:15"),
                TimeDetail(7, "14:20", "15:05"),
                TimeDetail(8, "15:20", "16:05"),
                TimeDetail(9, "16:10", "16:55"),
                TimeDetail(10, "17:05", "17:50"),
                TimeDetail(11, "17:55", "18:40"),
                TimeDetail(12, "19:20", "20:05"),
                TimeDetail(13, "20:10", "20:55"),
                TimeDetail(14, "21:00", "21:45")
            )
        )
    }

    // 学期数据

    var semester = ""

    fun getRescheduleData(): Array<Reschedule> {
        return when (semester) {
            "2022-2023-1" -> arrayOf(
                Reschedule(fromWeek = 1, fromDay = 1),
                Reschedule(fromWeek = 3, fromDay = 6),
                Reschedule(fromWeek = 3, fromDay = 7),
                Reschedule(fromWeek = 4, fromDay = 1, toWeek = 4, toDay = 6),
                Reschedule(fromWeek = 4, fromDay = 2),
                Reschedule(fromWeek = 4, fromDay = 3),
                Reschedule(fromWeek = 4, fromDay = 4),
                Reschedule(fromWeek = 4, fromDay = 5),
                Reschedule(fromWeek = 16, fromDay = 7)
            )
            else -> emptyArray()
        }
    }

    fun getTotalWeeks(): Int {
        return 16
    }

    // 课程表解析

    var secondaryCoursesDetails = mapOf<String, CourseDetails>()  // contains teacher and notes

    val semesterRegex = Regex("""name="p_xnxq" value="([\d\-]+?)"""")

    override fun generateCourseList(): List<Course> {
        semester = semesterRegex.find(source)?.groupValues?.get(1) ?: ""

        parseSecondaryCourseTable()  // generates secondaryCoursesDetails
        return parseCourses()
    }

    val mainScriptRegex = Regex("""setInitValue\(\).+setInitValue""", RegexOption.DOT_MATCHES_ALL)
    val cellPositionRegex = Regex("""a(\d)_(\d)""")
    val blueTextRegex = Regex("""<font color='blue'>([^<>]+?)</font>""")
    val courseNumberRegex = Regex("""\d{10};(\d{8})""")  // teacher ID; course number

    fun parseCourses(): List<Course> {
        val courseList = mutableListOf<Course>()
        val totalWeeks = getTotalWeeks()
        val script = mainScriptRegex.find(source)!!.value
        var courseInfo = CourseDetails()
        for (line in script.lines()) {
            if ("strHTML += \"" in line) {
                if ("<a " in line) {
                    courseInfo.number = courseNumberRegex.find(line)?.groupValues?.get(1) ?: ""
                } else if ("<b>" in line) {
                    courseInfo.name = line.substringAfter("<b>").substringBefore("</b>").trim()
                }
            } else if ("strHTML1 +=" in line) {
                courseInfo.params.add(line.substringAfter("；").substringBefore("\"").trim())
            } else if ("blue_red_none" in line) {
                // secondary courses
                var topic = ""
                blueTextRegex.findAll(line).forEachIndexed { i, result ->
                    when (i) {
                        0 -> courseInfo.name = result.groupValues[1].trim()
                        1 -> {
                            var details = result.groupValues[1].trim()
                            if (details.startsWith("(") && details.count { it == '(' } >= 2) {
                                topic = details.substringBefore(")") + ")"
                                details = details.substringAfter(")")
                            }
                            details = details.substringAfter("(").substringBefore(")").trim()
                            details.split("；").forEach {
                                when {
                                    "周" in it -> courseInfo.weeks = it
                                    "时间：" in it -> courseInfo.time = it.removePrefix("时间：")
                                    courseInfo.location.isBlank() -> courseInfo.location = it.trim()
                                }
                            }
                        }
                    }
                }
                courseInfo.location += topic
                secondaryCoursesDetails[courseInfo.name]?.let {
                    courseInfo.teacher = it.teacher
                    courseInfo.notes = it.notes
                }
            } else if ("getElementById" in line) {
                // finalize
                val (_, node, day) = cellPositionRegex.find(line)!!.groupValues
                if (courseInfo.params.isNotEmpty()) {
                    for (it in courseInfo.params.asReversed()) {
                        when {
                            // 倒数第一个以周结尾的是周数
                            it.endsWith("周") && courseInfo.weeks.isBlank() -> courseInfo.weeks = it
                            // 上课教室在周数的后面
                            courseInfo.weeks.isBlank() -> courseInfo.location = it
                            // 周数前面的不是课程属性的是教师（有可能空）
                            it !in Common.courseProperty -> courseInfo.teacher = it
                        }
                    }
                }
                val course = Course(
                    name = courseInfo.name,
                    day = day.toInt(),
                    room = courseInfo.location,
                    teacher = courseInfo.teacher,
                    startNode = startNodeMap[node.toInt()],
                    endNode = endNodeMap[node.toInt()],
                    startWeek = 0,
                    endWeek = 0,
                    type = -1,
                    credit = when (courseInfo.number) {
                        "" -> 0.0f
                        else -> courseInfo.number.last().toFloat() - 48 // char to float: ASCII
                    },
                    note = courseInfo.notes,
                    startTime = when (courseInfo.time) {
                        "" -> ""
                        else -> courseInfo.time.substringBefore("-").timeZeroPad()
                    },
                    endTime = when (courseInfo.time) {
                        "" -> ""
                        else -> courseInfo.time.substringAfter("-").timeZeroPad()
                    }
                )
                val weekIntList = parseWeeks(courseInfo.weeks.trim(), totalWeeks)
                for (it in getRescheduleData()) {
                    if (course.day == it.toDay && it.toWeek in weekIntList) {
                        weekIntList.remove(it.toWeek)
                    }
                    if (course.day == it.fromDay && it.fromWeek in weekIntList) {
                        weekIntList.remove(it.fromWeek)
                        if (it.toWeek > 0) {
                            if (course.day == it.toDay && it.toWeek !in weekIntList) {
                                // 目标日的课程已被清除
                                weekIntList.add(it.toWeek)
                            } else {
                                courseList.add(
                                    course.copy(
                                        day = it.toDay,
                                        startWeek = it.toWeek,
                                        endWeek = it.toWeek,
                                        type = 0
                                    )
                                )
                            }
                        }
                    }
                }
                weekIntList.sort()
                Common.weekIntList2WeekBeanList(weekIntList).mapTo(courseList) { week ->
                    course.copy(
                        startWeek = week.start,
                        endWeek = week.end,
                        type = week.type
                    )
                }
                courseInfo = CourseDetails()  // reset
            }
        }
        return courseList
    }

    val secondaryCourseTableHeaderRegex = Regex("""var gridColumns = \[(.+)];""", RegexOption.DOT_MATCHES_ALL)
    val secondaryCourseTableDataRegex = Regex("""var gridData = \[(.+)];""", RegexOption.DOT_MATCHES_ALL)
    val bracketsRegex = Regex("""\[([^\[\]]+)]""")

    fun parseSecondaryCourseTable() {
        val result = mutableMapOf<String, CourseDetails>()
        val header = secondaryCourseTableHeaderRegex.find(source)?.run { groupValues[1] } ?: return
        val data = secondaryCourseTableDataRegex.find(source)?.run { groupValues[1] } ?: return
        run {
            var nameIndex = 0
            var teacherIndex = 0
            var notesIndex = 0
            header.split(",").forEachIndexed { i, s ->
                when {
                    "课程名" in s -> nameIndex = i
                    "任课教师" in s -> teacherIndex = i
                    "选课文字说明" in s -> notesIndex = i
                }
            }
            for (arrayMatch in bracketsRegex.findAll(data)) {
                val array = arrayMatch.groupValues[1]
                if ("北大" in array || "北外" in array) continue
                val items = array.split(",")
                val name = items[nameIndex].trim().removeSurrounding("\"")
                val teacher = items[teacherIndex].trim().removeSurrounding("\"")
                val notes = items[notesIndex].trim().removeSurrounding("\"")
                result[name] = CourseDetails(teacher = teacher, notes = notes)
            }
        }
        secondaryCoursesDetails = result
    }

    fun parseWeeks(courseWeeks: String, totalWeeks: Int): MutableList<Int> {
        return when (courseWeeks) {
            "全周" -> 1..totalWeeks
            "前八周" -> 1..8
            "后八周" -> 9..totalWeeks
            "单周" -> 1..totalWeeks step 2
            "双周" -> 2..totalWeeks step 2
            else -> {
                if (!courseWeeks.endsWith("周")) {
                    return mutableListOf()
                }
                val courseWeeksRanges = courseWeeks
                    .removePrefix("第")
                    .removeSuffix("周")
                    .split(",")
                val weekIntList = mutableListOf<Int>()
                courseWeeksRanges.forEach {
                    when {
                        "-" in it ->
                            (it.substringBefore("-").toInt()..it.substringAfter("-").toInt())
                                .let { range -> weekIntList.addAll(range) }
                        else -> weekIntList.add(it.toInt())
                    }
                }
                return weekIntList
            }
        }.toMutableList()
    }

    data class Reschedule(
        val fromWeek: Int,
        val fromDay: Int,
        val toWeek: Int = 0,
        val toDay: Int = 0
    )

    data class CourseDetails(
        var number: String = "",
        var name: String = "",
        var teacher: String = "",
        var weeks: String = "",
        var location: String = "", // 二级课程内容加在这里
        var notes: String = "",
        var time: String = "",
        val params: MutableList<String> = mutableListOf()
    )

    fun String.timeZeroPad(): String {
        return if (this.length == 4 && this[0].isDigit() && this[1] == ':' && this[2].isDigit() && this[3].isDigit()) {
            "0$this"
        } else {
            this
        }
    }
}
