package com.pause.dance

import org.bytedeco.opencv.global.opencv_imgproc.circle
import org.bytedeco.opencv.global.opencv_imgproc.line
import org.bytedeco.opencv.opencv_core.Mat
import org.bytedeco.opencv.opencv_core.Point
import org.bytedeco.opencv.opencv_core.Scalar

class VisualizerConfig(
    val skeleton: List<List<Int>>,
    val sigmas: List<Float>,
    val palette: List<List<Int>>,
    val linkColor: List<Int>,
    val pointColor: List<Int>,
)

val COCO_VISUALIZATION_CONFIG = VisualizerConfig(
    skeleton=listOf(
        listOf(15, 13), listOf(13, 11), listOf(16, 14), listOf(14, 12), listOf(11, 12), listOf(5, 11),
        listOf(6, 12), listOf(5, 6), listOf(5, 7), listOf(6, 8), listOf(7, 9), listOf(8, 10), listOf(1, 2),
        listOf(0, 1), listOf(0, 2), listOf(1, 3), listOf(2, 4), listOf(3, 5), listOf(4, 6)
    ),
    sigmas=listOf(
        0.026, 0.025, 0.025, 0.035, 0.035, 0.079, 0.079, 0.072, 0.072,
        0.062, 0.062, 0.107, 0.107, 0.087, 0.087, 0.089, 0.089
    ).map { it.toFloat() },
    palette=listOf(
        listOf(255, 128, 0), listOf(255, 153, 51), listOf(255, 178, 102), listOf(230, 230, 0),
        listOf(255, 153, 255), listOf(153, 204, 255), listOf(255, 102, 255),
        listOf(255, 51, 255), listOf(102, 178, 255), listOf(51, 153, 255),
        listOf(255, 153, 153), listOf(255, 102, 102), listOf(255, 51, 51),
        listOf(153, 255, 153), listOf(102, 255, 102), listOf(51, 255, 51), listOf(0, 255, 0),
        listOf(0, 0, 255), listOf(255, 0, 0), listOf(255, 255, 255)
    ),
    linkColor = listOf(
        0, 0, 0, 0, 7, 7, 7, 9, 9, 9, 9, 9, 16, 16, 16, 16, 16, 16, 16
    ),
    pointColor = listOf(
        16, 16, 16, 16, 16, 9, 9, 9, 9, 9, 9, 0, 0, 0, 0, 0, 0
    )
)

fun visualizePose(
    frame: Mat,
    poseResults: List<PoseResult>,
    config: VisualizerConfig,
    threshold: Float = 0.5f
): Mat {
    for (pose in poseResults) {
        val keyPointShown = BooleanArray(pose.keypoints.size) { false }
        for (i in 0 until config.skeleton.size) {
            val pair = config.skeleton[i]
            if (pose.keypoints[pair[0]].score < threshold || pose.keypoints[pair[1]].score < threshold) continue

            val kp1 = pose.keypoints[pair[0]]
            val kp2 = pose.keypoints[pair[1]]

            val c = config.palette[config.linkColor[i]]

            line(
                frame,
                Point(kp1.x.toInt(), kp1.y.toInt()),
                Point(kp2.x.toInt(), kp2.y.toInt()),
                Scalar(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), 255.0),
            )
            keyPointShown[pair[0]] = true
            keyPointShown[pair[1]] = true
        }
        for (i in 0 until pose.keypoints.size) {
            if (!keyPointShown[i]) continue
            val keypoint = pose.keypoints[i]
            val c = config.palette[config.pointColor[i]]
            circle(
                frame,
                Point(keypoint.x.toInt(), keypoint.y.toInt()),
                5,
                Scalar(c[0].toDouble(), c[1].toDouble(), c[2].toDouble(), 255.0),
            )
        }
    }
    return frame
}


enum class COCODetClasses {
    PERSON,
    BICYCLE,
    CAR,
    MOTORCYCLE,
    AIRPLANE,
    BUS,
    TRAIN,
    TRUCK,
    BOAT,
    TRAFFIC_LIGHT,
    FIRE_HYDRANT,
    STOP_SIGN,
    PARKING_METER,
    BENCH,
    BIRD,
    CAT,
    DOG,
    HORSE,
    SHEEP,
    COW,
    ELEPHANT,
    BEAR,
    ZEBRA,
    GIRAFFE,
    BACKPACK,
    UMBRELLA,
    HANDBAG,
    TIE,
    SUITCASE,
    FRISBEE,
    SKIS,
    SNOWBOARD,
    SPORTS_BALL,
    KITE,
    BASEBALL_BAT,
    BASEBALL_GLOVE,
    SKATEBOARD,
    SURFBOARD,
    TENNIS_RACKET,
    BOTTLE,
    WINE_GLASS,
    CUP,
    FORK,
    KNIFE,
    SPOON,
    BOWL,
    BANANA,
    APPLE,
    SANDWICH,
    ORANGE,
    BROCCOLI,
    CARROT,
    HOT_DOG,
    PIZZA,
    DONUT,
    CAKE,
    CHAIR,
    COUCH,
    POTTED_PLANT,
    BED,
    DINING_TABLE,
    TOILET,
    TV,
    LAPTOP,
    MOUSE,
    REMOTE,
    KEYBOARD,
    CELL_PHONE,
    MICROWAVE,
    OVEN,
    TOASTER,
    SINK,
    REFRIGERATOR,
    BOOK,
    CLOCK,
    VASE,
    SCISSORS,
    TEDDY_BEAR,
    HAIR_DRIER,
    TOOTHBRUSH,
}

