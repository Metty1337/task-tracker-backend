package metty1337.task.tracker.backend.mapper;

import metty1337.task.tracker.backend.dto.TaskResponse;

import metty1337.task.tracker.backend.entity.Task;

import metty1337.task.tracker.backend.dto.CreateTaskRequest;

import metty1337.task.tracker.backend.entity.User;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;

import java.util.List;

@Mapper(componentModel = MappingConstants.ComponentModel.SPRING)
public interface TaskMapper {
    TaskResponse toResponse(Task task);

    List<TaskResponse> toResponses(List<Task> tasks);

    @Mapping(target = "id", ignore = true)
    @Mapping(target = "title", source = "request.title")
    @Mapping(target = "description", source = "request.description")
    @Mapping(target = "owner", source = "owner")
    @Mapping(target = "status", ignore = true)
    @Mapping(target = "completedAt", ignore = true)
    Task toEntity(CreateTaskRequest request, User owner);
}
